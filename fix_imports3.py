import re

with open('android_2/app/src/main/java/com/directlink/client/MainActivity.kt', 'r') as f:
    content = f.read()

content = content.replace('.androidx.compose.ui.focus.onFocusChanged {', '.onFocusChanged {')
if 'import androidx.compose.ui.focus.onFocusChanged' not in content:
    content = content.replace('import androidx.compose.ui.focus.FocusRequester', 'import androidx.compose.ui.focus.FocusRequester\nimport androidx.compose.ui.focus.onFocusChanged')

with open('android_2/app/src/main/java/com/directlink/client/MainActivity.kt', 'w') as f:
    f.write(content)
