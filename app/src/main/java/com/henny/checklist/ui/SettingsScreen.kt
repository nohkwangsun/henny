package com.henny.checklist.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.henny.checklist.data.Backend
import com.henny.checklist.data.Plan
import com.henny.checklist.data.Repository
import com.henny.checklist.data.Role
import com.henny.checklist.data.Settings
import com.henny.checklist.data.minuteToText
import com.henny.checklist.notify.AlarmScheduler
import com.henny.checklist.notify.Notifications
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    repo: Repository,
    settings: Settings,
    plan: Plan,
    isParent: Boolean
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val requestNotifications = LocalNotificationRequester.current

    var addingChild by remember { mutableStateOf(false) }
    var codeFor by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("설정", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 알림
        SectionCard(title = "알림") {
            val granted = Notifications.canPost(context)
            Text(
                text = if (granted) "알림이 켜져 있어요." else "알림이 꺼져 있어 아무것도 울리지 않아요.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (granted) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!granted) {
                    Button(onClick = requestNotifications) { Text("알림 켜기") }
                }
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("알림 설정 열기") }
            }
            if (Build.VERSION.SDK_INT >= 31) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "알람이 제때 울리지 않으면 '알람 및 리마인더' 권한을 켜주세요.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text("알람 권한 열기") }
            }
            if (isParent) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("하루 요약 받기", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            minuteToText(settings.parentSummaryMinute),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.parentSummaryOn,
                        onCheckedChange = {
                            repo.updateSettings { s -> s.copy(parentSummaryOn = it) }
                            AlarmScheduler.reschedule(context)
                        }
                    )
                }
                TimeField("요약 받을 시각", settings.parentSummaryMinute) { m ->
                    if (m != null) {
                        repo.updateSettings { s -> s.copy(parentSummaryMinute = m) }
                        AlarmScheduler.reschedule(context)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---------------------------------------------------------- 아이들
        if (isParent) {
            SectionCard(title = "아이") {
                plan.children.forEachIndexed { index, child ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${child.emoji} ${child.name}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = settings.progressBins[child.id]
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { "연결됨" } ?: "저장 공간 없음",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { codeFor = child.id }) { Text("연결 코드") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { addingChild = true }) { Text("아이 추가") }
            }
            Spacer(Modifier.height(14.dp))
        }

        // ---------------------------------------------------------- 저장소
        SectionCard(title = "가족 저장소") {
            Text(
                text = backendHelp(settings.backendEnum),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            if (isParent) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BackendChip("안 씀", settings.backendEnum == Backend.NONE) {
                        repo.updateSettings { it.copy(backend = Backend.NONE.name) }
                    }
                    BackendChip("JSONBin", settings.backendEnum == Backend.JSONBIN) {
                        repo.updateSettings { it.copy(backend = Backend.JSONBIN.name) }
                    }
                    BackendChip("직접 주소", settings.backendEnum == Backend.HTTP) {
                        repo.updateSettings { it.copy(backend = Backend.HTTP.name) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (settings.backendEnum == Backend.JSONBIN) {
                    OutlinedTextField(
                        value = settings.apiKey,
                        onValueChange = { v -> repo.updateSettings { it.copy(apiKey = v.trim()) } },
                        label = { Text("JSONBin Master Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = !busy && settings.apiKey.isNotBlank() && plan.children.isNotEmpty(),
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = repo.provisionBins()
                                busy = false
                                Toast.makeText(
                                    context,
                                    result.fold(
                                        { "저장 공간을 만들었어요." },
                                        { "실패: ${it.message}" }
                                    ),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    ) { Text(if (busy) "만드는 중…" else "저장 공간 만들기") }
                }
                if (settings.backendEnum == Backend.HTTP) {
                    OutlinedTextField(
                        value = settings.planBin,
                        onValueChange = { v -> repo.updateSettings { it.copy(planBin = v.trim()) } },
                        label = { Text("계획 문서 주소 (GET/PUT 되는 URL)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    plan.children.forEach { child ->
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = settings.progressBins[child.id] ?: "",
                            onValueChange = { v ->
                                repo.updateSettings {
                                    it.copy(progressBins = it.progressBins + (child.id to v.trim()))
                                }
                            },
                            label = { Text("${child.name} 기록 주소") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                Text(
                    text = if (settings.backendEnum == Backend.NONE) "이 폰에서만 사용 중"
                    else "부모님 폰과 연결되어 있어요.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { pairing = true }) { Text("연결 코드 다시 입력") }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val result = repo.sync()
                            busy = false
                            Toast.makeText(
                                context,
                                result.fold({ "동기화 완료" }, { "실패: ${it.message}" }),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) { Text("지금 동기화") }
                if (settings.lastSyncError.isNotBlank()) {
                    Text(
                        settings.lastSyncError,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "이 기기") {
            Text(
                text = if (settings.roleEnum == Role.PARENT) "부모용으로 설정됨"
                else "${repo.childName(settings.childId)}의 폰",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                repo.updateSettings { Settings() }
                Toast.makeText(context, "처음부터 다시 설정합니다.", Toast.LENGTH_SHORT).show()
            }) {
                Text("이 기기 설정 초기화", color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    if (addingChild) {
        TextPromptDialog(
            title = "아이 추가",
            label = "이름",
            placeholder = "예: 민준",
            confirmText = "추가",
            onConfirm = { name ->
                val emoji = listOf("🦊", "🐧", "🐨", "🐯")[plan.children.size % 4]
                repo.addChild(name, emoji)
                addingChild = false
            },
            onDismiss = { addingChild = false }
        )
    }

    codeFor?.let { childId ->
        val code = repo.pairingCode(childId)
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { codeFor = null },
            title = { Text("${repo.childName(childId)} 연결 코드") },
            text = {
                Column {
                    Text(
                        "아이 폰에서 앱을 열고 '아이 폰이에요'를 고른 뒤 이 코드를 붙여넣으면 끝이에요.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = code,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, code),
                            "연결 코드 보내기"
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("보내기") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(code))
                        Toast.makeText(context, "복사했어요", Toast.LENGTH_SHORT).show()
                    }) { Text("복사") }
                    TextButton(onClick = { codeFor = null }) { Text("닫기") }
                }
            }
        )
    }

    if (pairing) {
        TextPromptDialog(
            title = "연결 코드 입력",
            label = "부모님이 보내준 코드",
            placeholder = "HENNY1:...",
            confirmText = "연결",
            onConfirm = { raw ->
                val result = repo.applyPairingCode(raw)
                pairing = false
                Toast.makeText(
                    context,
                    result.fold({ "$it 폰으로 연결됐어요." }, { "코드를 읽지 못했어요." }),
                    Toast.LENGTH_LONG
                ).show()
                if (result.isSuccess) {
                    scope.launch { repo.sync() }
                    AlarmScheduler.reschedule(context)
                }
            },
            onDismiss = { pairing = false }
        )
    }
}

@Composable
private fun BackendChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}

private fun backendHelp(backend: Backend): String = when (backend) {
    Backend.NONE -> "지금은 이 폰에만 저장돼요. 다른 폰과 공유하려면 저장소를 골라주세요."
    Backend.JSONBIN -> "jsonbin.io 에 가족용 문서를 만들어 주고받습니다. 무료 계정의 Master Key만 있으면 돼요."
    Backend.HTTP -> "GET/PUT 이 되는 아무 JSON 주소나 쓸 수 있어요. (jsonblob.com, npoint.io 등)"
}
