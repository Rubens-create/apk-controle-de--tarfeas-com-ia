package com.taskai.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class MoreDestination(val route: String, val title: String, val detail: String, val icon: ImageVector)

private val moreDestinations = listOf(
    MoreDestination("settings", "Configurações", "Personalize o aplicativo, os lembretes e seu assistente IA.", Icons.Outlined.Settings),
    MoreDestination("organize", "Categorias e projetos", "Dê contexto às tarefas com suas próprias cores e ícones.", Icons.Outlined.FolderOpen),
    MoreDestination("logs", "Histórico de atividades", "Acompanhe as alterações registradas no aplicativo.", Icons.Outlined.History),
    MoreDestination("backup", "Backup e restauração", "Exporte seus dados ou restaure um arquivo de backup.", Icons.Outlined.SaveAlt)
)

@Composable
fun MoreScreen(onNavigate: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Text("Do seu jeito", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Organize seu espaço e cuide dos seus dados.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(moreDestinations, key = { it.route }) { destination ->
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Abrir ${destination.title}") { onNavigate(destination.route) },
                colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp)) {
                        Icon(destination.icon, contentDescription = null, modifier = Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(destination.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(destination.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
