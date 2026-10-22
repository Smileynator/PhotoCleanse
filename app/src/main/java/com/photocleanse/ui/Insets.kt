package com.photocleanse.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun Modifier.systemBarsPaddingCompat(): Modifier = windowInsetsPadding(WindowInsets.systemBars)
