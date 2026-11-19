package ru.landilf.desklink.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class CheckStatus { YES, NO, PENDING, ERROR }

@Composable
fun StatusIndicator(status: CheckStatus, description: String) {
    val color = when (status) {
        CheckStatus.YES -> Color(0xFF4CAF50)
        CheckStatus.NO -> Color(0xFFE53935)
        CheckStatus.PENDING -> Color(0xFF9E9E9E)
        CheckStatus.ERROR -> Color(0xFFFFA000)
    }
    Box(Modifier.size(12.dp).semantics { contentDescription = description }.background(color, CircleShape))
}
