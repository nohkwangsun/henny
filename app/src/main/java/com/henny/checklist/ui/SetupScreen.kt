package com.henny.checklist.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.henny.checklist.data.Backend
import com.henny.checklist.data.Plan
import com.henny.checklist.data.Repository
import com.henny.checklist.data.Role
import com.henny.checklist.data.Settings
import com.henny.checklist.notify.AlarmScheduler
import kotlinx.coroutines.launch

private enum class Step { ROLE, PARENT_CHILDREN, PARENT_STORAGE, KID_PAIR, KID_SOLO }

@Composable
fun SetupScreen(repo: Repository, plan: Plan, settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestNotifications = LocalNotificationRequester.current

    var step by remember { mutableStateOf(Step.ROLE) }
    var addingChild by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var soloName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun finish() {
        repo.updateSettings { it.copy(setupDone = true) }
        requestNotifications()
        AlarmScheduler.reschedule(context)
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        Text("헨니 체크", style = MaterialTheme.typography.headlineLarge)
        Text(
            "떨어져 있어도, 오늘 할 일은 같이 챙겨요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        when (step) {
            Step.ROLE -> {
                SectionCard(title = "이 폰은 누구 폰인가요?") {
                    Button(
                        onClick = {
                            repo.updateSettings { it.copy(role = Role.PARENT.name) }
                            step = Step.PARENT_CHILDREN
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("부모 폰이에요") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            repo.updateSettings { it.copy(role = Role.KID.name) }
                            step = Step.KID_PAIR
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("아이 폰이에요") }
                }
            }

            Step.PARENT_CHILDREN -> {
                SectionCard(title = "아이를 등록해 주세요") {
                    if (plan.children.isEmpty()) {
                        Text(
                            "아이마다 할 일이 다르니 한 명씩 따로 등록해요.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    plan.children.forEach { child ->
                        Text(
                            "${child.emoji} ${child.name}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { addingChild = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("아이 추가") }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { step = Step.PARENT_STORAGE },
                        enabled = plan.children.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("다음") }
                }
            }

            Step.PARENT_STORAGE -> {
                SectionCard(title = "가족끼리 어떻게 주고받을까요?") {
                    Text(
                        "jsonbin.io 무료 계정을 만들고 Master Key 를 붙여넣으면, " +
                            "아이 폰과 자동으로 이어집니다. 나중에 설정에서 바꿔도 돼요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = settings.apiKey,
                        onValueChange = { v ->
                            repo.updateSettings {
                                it.copy(apiKey = v.trim(), backend = Backend.JSONBIN.name)
                            }
                        },
                        label = { Text("JSONBin Master Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !busy && settings.apiKey.isNotBlank(),
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = repo.provisionBins()
                                busy = false
                                if (result.isSuccess) {
                                    Toast.makeText(context, "준비 끝!", Toast.LENGTH_SHORT).show()
                                    finish()
                                } else {
                                    Toast.makeText(
                                        context,
                                        "실패: ${result.exceptionOrNull()?.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "만드는 중…" else "저장 공간 만들고 시작") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            repo.updateSettings { it.copy(backend = Backend.NONE.name) }
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("나중에 하기 (이 폰에서만 사용)") }
                }
            }

            Step.KID_PAIR -> {
                SectionCard(title = "부모님이 준 코드를 붙여넣어요") {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("연결 코드") },
                        placeholder = { Text("HENNY1:...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = code.isNotBlank(),
                        onClick = {
                            val result = repo.applyPairingCode(code)
                            if (result.isSuccess) {
                                scope.launch { repo.sync() }
                                Toast.makeText(
                                    context,
                                    "${result.getOrNull()} 폰으로 연결됐어요!",
                                    Toast.LENGTH_LONG
                                ).show()
                                finish()
                            } else {
                                Toast.makeText(context, "코드를 다시 확인해 주세요.", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("연결하기") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { step = Step.KID_SOLO },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("코드 없이 이 폰에서만 쓰기") }
                }
            }

            Step.KID_SOLO -> {
                SectionCard(title = "이름을 알려주세요") {
                    OutlinedTextField(
                        value = soloName,
                        onValueChange = { soloName = it },
                        label = { Text("이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = soloName.isNotBlank(),
                        onClick = {
                            val child = repo.addChild(soloName.trim(), "🦊")
                            repo.updateSettings {
                                it.copy(
                                    role = Role.KID.name,
                                    childId = child.id,
                                    backend = Backend.NONE.name
                                )
                            }
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("시작하기") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (step != Step.ROLE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { step = Step.ROLE }) { Text("← 처음으로") }
            }
        }
        Spacer(Modifier.height(40.dp))
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
}
