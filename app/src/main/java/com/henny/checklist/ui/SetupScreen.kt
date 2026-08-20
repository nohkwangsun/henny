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

private enum class Step { ROLE, MANAGER_WORKERS, MANAGER_STORAGE, WORKER_PAIR, WORKER_SOLO }

@Composable
fun SetupScreen(repo: Repository, plan: Plan, settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestNotifications = LocalNotificationRequester.current

    var step by remember { mutableStateOf(Step.ROLE) }
    var addingWorker by remember { mutableStateOf(false) }
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
            "오늘 할 작업을 한눈에.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(28.dp))

        when (step) {
            Step.ROLE -> {
                SectionCard(title = "이 기기는 어느 쪽인가요?") {
                    Button(
                        onClick = {
                            repo.updateSettings { it.copy(role = Role.MANAGER.name) }
                            step = Step.MANAGER_WORKERS
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("관리자 기기예요") }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            repo.updateSettings { it.copy(role = Role.WORKER.name) }
                            step = Step.WORKER_PAIR
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("작업자 기기예요") }
                    Spacer(Modifier.height(10.dp))
                    TextButton(
                        onClick = { step = Step.WORKER_PAIR },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("쓰던 기기 복구 (코드 있음)") }
                }
            }

            Step.MANAGER_WORKERS -> {
                SectionCard(title = "작업자를 등록해 주세요") {
                    if (plan.workers.isEmpty()) {
                        Text(
                            "작업자마다 할 일이 다르니 한 명씩 따로 등록합니다. " +
                                "실명 대신 표시 이름을 써도 됩니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    plan.workers.forEach { worker ->
                        Text(
                            worker.label,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { addingWorker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("작업자 추가") }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { step = Step.MANAGER_STORAGE },
                        enabled = plan.workers.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("다음") }
                }
            }

            Step.MANAGER_STORAGE -> {
                SectionCard(title = "기기끼리 어떻게 주고받을까요?") {
                    Text(
                        "구글 Firebase 무료 데이터베이스를 쓰면 자료가 내 구글 계정 안에 남습니다. " +
                            "콘솔에서 만든 주소를 붙여넣으면 작업자 기기와 이어집니다. " +
                            "다른 저장소는 나중에 설정 탭에서 고를 수 있어요.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = settings.firebaseDb,
                        onValueChange = { v ->
                            repo.updateSettings {
                                it.copy(firebaseDb = v.trim(), backend = Backend.FIREBASE.name)
                            }
                        },
                        label = { Text("실시간 데이터베이스 주소") },
                        placeholder = { Text("https://…firebasedatabase.app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settings.apiKey,
                        onValueChange = { v -> repo.updateSettings { it.copy(apiKey = v.trim()) } },
                        label = { Text("비밀키 (규칙으로 열어뒀다면 비워두세요)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !busy && settings.firebaseDb.isNotBlank(),
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
                    ) { Text(if (busy) "연결 중…" else "연결하고 시작") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            repo.updateSettings { it.copy(backend = Backend.NONE.name) }
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("나중에 하기 (이 기기에서만 사용)") }
                }
            }

            Step.WORKER_PAIR -> {
                SectionCard(title = "코드를 붙여넣으세요") {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("연결 코드 또는 복구 코드") },
                        placeholder = { Text("HENNY2:...") },
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
                                    "${result.getOrNull()} 기기로 연결됐습니다.",
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
                        onClick = { step = Step.WORKER_SOLO },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("코드 없이 이 기기에서만 쓰기") }
                }
            }

            Step.WORKER_SOLO -> {
                SectionCard(title = "표시 이름을 입력하세요") {
                    OutlinedTextField(
                        value = soloName,
                        onValueChange = { soloName = it },
                        label = { Text("이름 또는 표시 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = soloName.isNotBlank(),
                        onClick = {
                            val worker = repo.addWorker(soloName.trim(), "")
                            repo.updateSettings {
                                it.copy(
                                    role = Role.WORKER.name,
                                    workerId = worker.id,
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

    if (addingWorker) {
        TextPromptDialog(
            title = "작업자 추가",
            label = "이름 또는 표시 이름",
            placeholder = "예: 김민준, 야간조",
            confirmText = "추가",
            onConfirm = { name ->
                                repo.addWorker(name, "")
                addingWorker = false
            },
            onDismiss = { addingWorker = false }
        )
    }
}
