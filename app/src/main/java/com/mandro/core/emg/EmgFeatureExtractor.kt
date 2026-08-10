package com.mandro.core.emg

import com.mandro.domain.model.EMG_CHANNELS
import com.mandro.domain.model.WINDOW_SIZE
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 128샘플 × 8채널 EMG 윈도우 → 132개 특징 벡터
 *
 * 백엔드 features/features.py 와 동일한 순서 · 수식으로 구현.
 * 동일한 raw EMG를 입력했을 때 최대 절대 오차 < 1e-4 이어야 함.
 *
 * ── Classic 88 ────────────────────────────────────────────────
 * TD (feature-first, 6 × 8 = 48):
 *   [MAV×8] [MaxAV×8] [STD×8] [RMS×8] [WL×8] [SSC×8]
 *
 * FD (channel-first, 8 × 5 = 40):
 *   [MeanPow, TotalPow, MeanFreq, MedianFreq, PeakFreq] × ch0~ch7
 *
 * ── TSD 44 ────────────────────────────────────────────────────
 * Covariance upper triangle (36):
 *   (0,0)(0,1)…(0,7)(1,1)…(7,7)  — numpy.cov 기준 (N-1 나눗셈)
 *
 * Channel energy (8):
 *   sum(x²) per channel, ch0~ch7
 */
object EmgFeatureExtractor {

    const val FEATURE_SIZE = 132  // 48 + 40 + 36 + 8
    private const val FS = 1281f  // BLE 샘플링 주파수 (Hz)

    /**
     * @param window shape: [WINDOW_SIZE=128][EMG_CHANNELS=8]
     * @return FloatArray(132)
     */
    fun extract(window: Array<FloatArray>): FloatArray {
        val n   = window.size          // 128
        val nCh = window[0].size       // 8

        // 행렬 전치 → ch[c][t]: 채널 단위 접근이 더 빠름
        val ch = Array(nCh) { c -> FloatArray(n) { t -> window[t][c] } }

        val out = FloatArray(FEATURE_SIZE)
        var idx = 0

        // ── Classic TD: feature-first (6 피처 × 8채널 = 48) ──────
        for (c in 0 until nCh) out[idx++] = mav(ch[c])
        for (c in 0 until nCh) out[idx++] = maxAv(ch[c])
        for (c in 0 until nCh) out[idx++] = std(ch[c])
        for (c in 0 until nCh) out[idx++] = rms(ch[c])
        for (c in 0 until nCh) out[idx++] = wl(ch[c])
        for (c in 0 until nCh) out[idx++] = ssc(ch[c])

        // ── Classic FD: channel-first (8채널 × 5 피처 = 40) ──────
        val psds = Array(nCh) { c -> psd(ch[c], n) }
        for (c in 0 until nCh) {
            val p = psds[c]
            out[idx++] = meanPow(p)
            out[idx++] = totalPow(p)
            out[idx++] = meanFreq(p, n)
            out[idx++] = medianFreq(p, n)
            out[idx++] = peakFreq(p, n)
        }

        // ── TSD 공분산 상삼각 (36) ────────────────────────────────
        for (i in 0 until nCh) {
            for (j in i until nCh) {
                out[idx++] = cov(ch[i], ch[j])
            }
        }

        // ── TSD 채널별 에너지 (8) ─────────────────────────────────
        for (c in 0 until nCh) out[idx++] = energy(ch[c])

        check(idx == FEATURE_SIZE) { "특징 수 불일치: $idx != $FEATURE_SIZE" }
        return out
    }

    // ── 시간영역 피처 ──────────────────────────────────────────────

    /** Mean Absolute Value */
    private fun mav(x: FloatArray) =
        x.sumOf { abs(it).toDouble() }.toFloat() / x.size

    /** Maximum Absolute Value */
    private fun maxAv(x: FloatArray) = x.maxOf { abs(it) }

    /** Standard Deviation (population, numpy 기본값과 동일하게 N 나눗셈) */
    private fun std(x: FloatArray): Float {
        val mean = x.average().toFloat()
        return sqrt(x.sumOf { ((it - mean) * (it - mean)).toDouble() }.toFloat() / x.size)
    }

    /** Root Mean Square */
    private fun rms(x: FloatArray) =
        sqrt(x.sumOf { (it * it).toDouble() }.toFloat() / x.size)

    /** Waveform Length */
    private fun wl(x: FloatArray): Float {
        var s = 0f
        for (i in 1 until x.size) s += abs(x[i] - x[i - 1])
        return s
    }

    /** Slope Sign Change (연속 기울기 부호 변화 횟수) */
    private fun ssc(x: FloatArray): Float {
        var count = 0
        for (i in 1 until x.size - 1) {
            if ((x[i] - x[i - 1]) * (x[i + 1] - x[i]) < 0f) count++
        }
        return count.toFloat()
    }

    // ── 주파수영역 피처 ────────────────────────────────────────────

    /**
     * One-sided Power Spectral Density (rfft 기반).
     * PSD[k] = |FFT[k]|² / N  (k=0 및 N/2 제외 → ×2 보정)
     * numpy.fft.rfft 와 동일한 정규화.
     */
    private fun psd(x: FloatArray, n: Int): FloatArray {
        val (re, im) = rfft(x)
        val half = n / 2 + 1
        return FloatArray(half) { k ->
            val p = re[k] * re[k] + im[k] * im[k]
            when (k) {
                0, n / 2 -> p / n
                else     -> 2f * p / n
            }
        }
    }

    /** 주파수 축: fs * k / N  (k = 0 … N/2) */
    private fun freqs(n: Int) = FloatArray(n / 2 + 1) { k -> k * FS / n }

    /** Mean Power = mean(PSD) */
    private fun meanPow(psd: FloatArray) = psd.sum() / psd.size

    /** Total Power = sum(PSD) */
    private fun totalPow(psd: FloatArray) = psd.sum()

    /** Mean Frequency = sum(f × P) / sum(P) */
    private fun meanFreq(psd: FloatArray, n: Int): Float {
        val f = freqs(n)
        val total = psd.sum()
        if (total < 1e-12f) return 0f
        return psd.indices.sumOf { (f[it] * psd[it]).toDouble() }.toFloat() / total
    }

    /** Median Frequency: 누적 전력이 50%에 도달하는 주파수 */
    private fun medianFreq(psd: FloatArray, n: Int): Float {
        val f = freqs(n)
        val half = psd.sum() / 2f
        var cum = 0f
        for (i in psd.indices) {
            cum += psd[i]
            if (cum >= half) return f[i]
        }
        return f.last()
    }

    /** Peak Frequency: 최대 전력 주파수 */
    private fun peakFreq(psd: FloatArray, n: Int): Float {
        val f = freqs(n)
        return f[psd.indices.maxByOrNull { psd[it] } ?: 0]
    }

    // ── TSD 피처 ───────────────────────────────────────────────────

    /**
     * 표본 공분산 (numpy.cov 기본값 = N-1 나눗셈).
     * i == j 이면 표본 분산.
     */
    private fun cov(x: FloatArray, y: FloatArray): Float {
        val n  = x.size
        val mx = x.average().toFloat()
        val my = y.average().toFloat()
        return x.indices.sumOf { ((x[it] - mx) * (y[it] - my)).toDouble() }
            .toFloat() / (n - 1)
    }

    /** Channel Energy = sum(x²) */
    private fun energy(x: FloatArray) = x.sumOf { (it * it).toDouble() }.toFloat()

    // ── Cooley-Tukey FFT (radix-2, in-place) ──────────────────────

    /**
     * Real-input FFT → (re[0..N/2], im[0..N/2]).
     * 입력 크기는 2의 거듭제곱이어야 함 (128 = 2^7 OK).
     */
    private fun rfft(x: FloatArray): Pair<FloatArray, FloatArray> {
        val n  = x.size
        val re = x.copyOf()
        val im = FloatArray(n)

        // Bit-reversal permutation
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        // Butterfly
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val ang = -2.0 * PI / len
            val wBaseRe = cos(ang).toFloat()
            val wBaseIm = sin(ang).toFloat()
            var start = 0
            while (start < n) {
                var wRe = 1f; var wIm = 0f
                for (k in 0 until halfLen) {
                    val uRe = re[start + k];          val uIm = im[start + k]
                    val vRe = re[start + k + halfLen]; val vIm = im[start + k + halfLen]
                    val tvRe = vRe * wRe - vIm * wIm
                    val tvIm = vRe * wIm + vIm * wRe
                    re[start + k]          = uRe + tvRe; im[start + k]          = uIm + tvIm
                    re[start + k + halfLen] = uRe - tvRe; im[start + k + halfLen] = uIm - tvIm
                    val newWRe = wRe * wBaseRe - wIm * wBaseIm
                    wIm = wRe * wBaseIm + wIm * wBaseRe
                    wRe = newWRe
                }
                start += len
            }
            len *= 2
        }

        // One-sided (0 … N/2)
        val half = n / 2 + 1
        return re.copyOf(half) to im.copyOf(half)
    }
}
