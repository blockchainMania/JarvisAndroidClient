package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.people.PersonItem

// Reusable pieces shared with MemoryScreen's "사람" tab -- there's no standalone people screen
// entry point; the outer Scaffold/TopAppBar/tab row all live in MemoryScreen so people share the
// same "Jarvis Records" hub as memories/meetings instead of needing a separate nav target.

@Composable
internal fun PeopleSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("이름/소속 검색") },
            placeholder = { Text("예: 김윤섭, DH배터리") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSearch) {
            Icon(Icons.Default.Search, contentDescription = "Search")
        }
    }
}

@Composable
internal fun PersonCard(
    person: PersonItem,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = person.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val subtitle = listOfNotNull(person.org, person.role).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val contact = listOfNotNull(person.phone, person.email).joinToString(" · ")
                if (contact.isNotBlank()) {
                    Text(
                        text = contact,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
internal fun PersonEditDialog(
    person: PersonItem,
    isSaving: Boolean,
    onDismiss: () -> Unit,
    onSave: (PersonItem) -> Unit,
) {
    var name by remember(person) { mutableStateOf(person.name) }
    var org by remember(person) { mutableStateOf(person.org.orEmpty()) }
    var role by remember(person) { mutableStateOf(person.role.orEmpty()) }
    var phone by remember(person) { mutableStateOf(person.phone.orEmpty()) }
    var email by remember(person) { mutableStateOf(person.email.orEmpty()) }
    var address by remember(person) { mutableStateOf(person.address.orEmpty()) }
    var notesSummary by remember(person) { mutableStateOf(person.notesSummary.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("사람 정보 수정") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("이름") }, singleLine = true)
                OutlinedTextField(value = org, onValueChange = { org = it }, label = { Text("소속") }, singleLine = true)
                OutlinedTextField(value = role, onValueChange = { role = it }, label = { Text("직책") }, singleLine = true)
                OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("전화번호") }, singleLine = true)
                OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("이메일") }, singleLine = true)
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("주소") }, singleLine = true)
                OutlinedTextField(value = notesSummary, onValueChange = { notesSummary = it }, label = { Text("메모") })
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving && name.isNotBlank(),
                onClick = {
                    onSave(
                        person.copy(
                            name = name.trim(),
                            org = org.trim().ifBlank { null },
                            role = role.trim().ifBlank { null },
                            phone = phone.trim().ifBlank { null },
                            email = email.trim().ifBlank { null },
                            address = address.trim().ifBlank { null },
                            notesSummary = notesSummary.trim().ifBlank { null },
                        )
                    )
                },
            ) { Text("저장") }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) { Text("취소") }
        },
    )
}
