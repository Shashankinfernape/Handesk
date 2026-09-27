import os

ma_path = 'android/app/src/main/java/com/directlink/client/MainActivity.kt'
with open(ma_path, 'r', encoding='utf-8') as f:
    ma_content = f.read()

target = '''                        }
                            println("DirectLink: UDP P2P Connection SUCCESS! ($localIp)")
                            useWebsocket = false
                            networkClient.inputPacketSender = null

                    }
                } else if (json.optString("type") == "RELAY_ALLOCATED") {'''

replacement = '''                        }
                    }
                } else if (json.optString("type") == "RELAY_ALLOCATED") {'''

ma_content = ma_content.replace(target, replacement)

with open(ma_path, 'w', encoding='utf-8') as f:
    f.write(ma_content)

print("Fixed!")
