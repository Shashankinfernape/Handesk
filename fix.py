with open('android/app/src/main/java/com/directlink/client/MainActivity.kt', 'r') as f:
    content = f.read()

target = """                        } else {
                            println("DirectLink: UDP Failed, falling back to Relay...")
                            val sessionId = json.optString("sessionId")
                            signalingClient.requestRelay(sessionId)
                        }
                    }
                }
            }
        }
        // Collect frames from the Cloudflare WebSocket relay!"""

replacement = """                        } else {
                            println("DirectLink: UDP Failed, falling back to Relay...")
                            val sessionId = json.optString("sessionId")
                            signalingClient.requestRelay(sessionId)
                        }
                    }
                } else if (json.optString("type") == "RELAY_ALLOCATED") {
                    val relayIp = json.optString("relayIp")
                    val relayPort = json.optInt("relayPort")
                    val sessionId = json.optString("sessionId")
                    println("DirectLink: Connecting to UDP Relay at " + relayIp + ":" + relayPort)
                    
                    val err = networkClient.connectToRelay(relayIp, relayPort, sessionId)
                    if (err == null) {
                        useWebsocket = false
                        networkClient.inputPacketSender = null
                        println("DirectLink: Connected to UDP Relay successfully!")
                    }
                }
            }
        }
        // Collect frames from the Cloudflare WebSocket relay!"""

if target in content:
    with open('android/app/src/main/java/com/directlink/client/MainActivity.kt', 'w') as f:
        f.write(content.replace(target, replacement))
    print('Success')
else:
    print('Target not found')
