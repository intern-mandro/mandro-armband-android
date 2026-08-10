package com.mandro.presentation.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mandro.domain.model.BleDevice
import com.mandro.domain.model.BleState
import com.mandro.domain.model.GestureSet
import com.mandro.domain.model.User
import com.mandro.presentation.components.ConnectionBadge
import com.mandro.presentation.components.MandroPrimaryButton
import com.mandro.presentation.theme.MandroPalette
import com.mandro.presentation.theme.MandroTheme

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onUserSelected: () -> Unit = {},
    onAddUser: () -> Unit = {},
    onConnectBand: () -> Unit = {},
    onResendWeights: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.navigateToMain.collect { onUserSelected() }
    }
    LaunchedEffect(Unit) {
        viewModel.navigateToBleScan.collect { onConnectBand() }
    }
    LaunchedEffect(Unit) {
        viewModel.navigateToFirmware.collect { onResendWeights() }
    }

    if (uiState.error != null) {
        AlertDialog(
            onDismissRequest = viewModel::onErrorDismissed,
            title = { Text("유저 선택 필요") },
            text = { Text(uiState.error!!) },
            confirmButton = {
                TextButton(onClick = viewModel::onErrorDismissed) { Text("확인") }
            },
        )
    }

    val deleteTarget = uiState.deleteTarget
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = viewModel::onDeleteUserCancelled,
            title = { Text("${deleteTarget.name}님을 삭제할까요?") },
            text = { Text("녹화된 데이터와 학습된 모델이 모두 삭제되고, 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = viewModel::onDeleteUserConfirmed) {
                    Text("삭제", color = MandroPalette.Danger600)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDeleteUserCancelled) { Text("취소") }
            },
        )
    }

    HomeContent(
        uiState = uiState,
        onUserClick = viewModel::onUserSelected,
        onAddUser = onAddUser,
        onConnectBand = viewModel::onConnectBand,
        onResendWeightsClick = viewModel::onResendWeights,
        onDeleteUserClick = viewModel::onDeleteUserRequested,
    )
}

@Composable
private fun HomeContent(
    uiState: HomeUiState,
    onUserClick: (User) -> Unit,
    onAddUser: () -> Unit,
    onConnectBand: () -> Unit,
    onResendWeightsClick: (User) -> Unit = {},
    onDeleteUserClick: (User) -> Unit = {},
) {
    Scaffold(
        containerColor = MandroPalette.Neutral50,
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = "안녕하세요 👋",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MandroPalette.Neutral900,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "유저를 선택하거나 새로 만드로 주세요",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MandroPalette.Neutral500,
                    )
                    Spacer(Modifier.height(24.dp))
                }

                item {
                    BandConnectionCard(bleState = uiState.bleState)
                    Spacer(Modifier.height(24.dp))
                }

                item {
                    Text(
                        text = "유저 선택",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MandroPalette.Neutral900,
                    )
                    Spacer(Modifier.height(12.dp))
                }

                items(uiState.users) { user ->
                    UserCard(
                        user = user,
                        isActive = user.id == uiState.activeUserId,
                        onClick = { onUserClick(user) },
                        onResendWeightsClick = { onResendWeightsClick(user) },
                        onDeleteClick = { onDeleteUserClick(user) },
                    )
                }

                item {
                    AddUserCard(onClick = onAddUser)
                    Spacer(Modifier.height(16.dp))
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 유저 카드를 새로 안 눌러도 지난 세션의 activeUserId로 그대로 연결되므로,
                // 어느 유저로 연결될지 버튼 위에 명시해서 실수로 다른 유저 데이터에
                // 녹화/학습하는 걸 방지.
                uiState.activeUser?.let { active ->
                    Text(
                        text = "${active.name}님으로 연결해요",
                        style = MaterialTheme.typography.labelSmall,
                        color = MandroPalette.Neutral500,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                MandroPrimaryButton(
                    text = "암밴드 연결하기",
                    onClick = onConnectBand,
                )
            }
        }
    }
}

@Composable
private fun BandConnectionCard(bleState: BleState) {
    val isConnected = bleState is BleState.Connected

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MandroPalette.Neutral100,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (isConnected) "🔗" else "🔌",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isConnected) "암밴드가 연결됐어요" else "암밴드가 연결되지 않았어요",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MandroPalette.Neutral900,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (isConnected) "연결 상태가 좋아요" else "아래 버튼을 눌러 암밴드를 연결해 주세요",
                    style = MaterialTheme.typography.labelSmall,
                    color = MandroPalette.Neutral500,
                )
            }
            ConnectionBadge(bleState = bleState)
        }
    }
}

@Composable
fun UserCard(
    user: User,
    onClick: () -> Unit,
    isActive: Boolean = false,
    onResendWeightsClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MandroPalette.White,
        shadowElevation = 1.dp,
        border = if (isActive) BorderStroke(1.5.dp, MandroPalette.Primary600) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MandroPalette.Primary100),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MandroPalette.Primary600,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MandroPalette.Neutral900,
                    )
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "현재 유저",
                            style = MaterialTheme.typography.labelSmall,
                            color = MandroPalette.Primary600,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (user.hasModel) "모델 있음" else "모델 없음",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (user.hasModel) MandroPalette.Success600 else MandroPalette.Neutral500,
                )
            }
            if (user.hasModel) {
                TextButton(onClick = onResendWeightsClick) {
                    Text(
                        text = "가중치 재전송",
                        style = MaterialTheme.typography.labelSmall,
                        color = MandroPalette.Primary600,
                    )
                }
            }
            IconButton(onClick = onDeleteClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "유저 삭제",
                    tint = MandroPalette.Neutral300,
                    modifier = Modifier.size(18.dp),
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MandroPalette.Neutral300,
            )
        }
    }
}

@Composable
private fun AddUserCard(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.5.dp,
                color = MandroPalette.Neutral300,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MandroPalette.Neutral500,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "새 유저 추가",
                style = MaterialTheme.typography.bodyMedium,
                color = MandroPalette.Neutral500,
            )
        }
    }
}

// ── 프리뷰 ────────────────────────────────────────────────────

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun HomePreview_WithUsers() {
    MandroTheme {
        HomeContent(
            uiState = HomeUiState(
                users = listOf(
                    User(name = "HAERIM", hasModel = true),
                    User(name = "KOTA", hasModel = true),
                    User(name = "S003", hasModel = true),
                ),
                bleState = BleState.Disconnected,
            ),
            onUserClick = {},
            onAddUser = {},
            onConnectBand = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun HomePreview_Empty() {
    MandroTheme {
        HomeContent(
            uiState = HomeUiState(
                users = emptyList(),
                bleState = BleState.Disconnected,
            ),
            onUserClick = {},
            onAddUser = {},
            onConnectBand = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF7F8FA)
@Composable
private fun HomePreview_Connected() {
    MandroTheme {
        HomeContent(
            uiState = HomeUiState(
                users = listOf(User(name = "HAERIM", hasModel = true)),
                bleState = BleState.Connected(
                    BleDevice("EMG-Sensor-1A2B", "00:11:22:33:44:55", -55)
                ),
            ),
            onUserClick = {},
            onAddUser = {},
            onConnectBand = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun UserCardPreview() {
    MandroTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UserCard(user = User(name = "HAERIM", hasModel = true, gestureSet = GestureSet.SIX_CLASS), onClick = {})
            UserCard(user = User(name = "S003", hasModel = false, gestureSet = GestureSet.FOUR_CLASS), onClick = {})
        }
    }
}
