package com.fifo.voicepipeline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fifo.voicepipeline.ui.theme.FifoColors

enum class FifoTab(val title: String, val activeIcon: ImageVector, val inactiveIcon: ImageVector) {
    FRIENDS("fifo amigos", Icons.Filled.People, Icons.Outlined.People),
    HOME("principal", Icons.Filled.Home, Icons.Outlined.Home),
    ACTIVITIES("actividades", Icons.Filled.MusicNote, Icons.Outlined.MusicNote),
    PROFILE("perfil", Icons.Filled.Person, Icons.Outlined.Person)
}

@Composable
fun FifoBottomNav(
    selectedTab: FifoTab,
    onTabSelected: (FifoTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FifoTab.values().forEach { tab ->
            val isSelected = tab == selectedTab
            val color = if (isSelected) FifoColors.NavyPrimary else FifoColors.LightTextMuted

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onTabSelected(tab) }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                // Indicador circular si está activo en "Principal" como en Figma
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isSelected && tab == FifoTab.HOME) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(FifoColors.BlueSoftPill, CircleShape)
                        )
                    }
                    Icon(
                        imageVector = if (isSelected) tab.activeIcon else tab.inactiveIcon,
                        contentDescription = tab.title,
                        tint = color,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = tab.title,
                    fontSize = 11.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = color
                )
            }
        }
    }
}
