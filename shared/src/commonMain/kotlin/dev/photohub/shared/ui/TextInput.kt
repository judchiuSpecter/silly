package dev.photohub.shared.ui

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Keyboard settings for every text field in the app.
 *
 * Hostnames, usernames, paths, dates and even email addresses are identifiers
 * rather than prose. Leaving autocorrect on lets the IME "helpfully" rewrite
 * them, so suggestions and auto-capitalisation are off everywhere.
 */
internal fun plainTextKeyboardOptions(
    keyboardType: KeyboardType = KeyboardType.Text,
): KeyboardOptions = KeyboardOptions(
    keyboardType = keyboardType,
    autoCorrectEnabled = false,
    capitalization = KeyboardCapitalization.None,
)
