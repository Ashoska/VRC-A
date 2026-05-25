# VRC-A Terms of Service

## 1. Agreement
By using VRC-A you accept these terms. If you disagree, uninstall the app.

## 2. What VRC-A Does
VRC-A is a companion app for VRChat. It provides:
- **Chatbox**: Sends customizable text (music status, time, pinned/cycling messages) to VRChat via OSC
- **VRChat Notifications**: Real-time alerts for friend activity (online/offline, location, status, bio, avatar changes), friend requests, unfriends, group announcements, events, and invites
- **Discord Rich Presence**: Displays your VRChat activity on your Discord profile using a background WebView session

## 3. Data & Privacy

**What we store:**
- An anonymous device identifier (SHA-256 hash not your Android ID directly)
- App settings, toggle states, and chatbox content in Firebase Firestore
- VRChat auth cookies in encrypted local storage (never sent to our servers)
- Discord session cookies in a local WebView (never extracted or transmitted)
- Friends list cached locally for change detection (not uploaded)

**What we don't collect:** Your name, email, phone number, IP address, or any personal identity information.

## 4. Required Permissions & Why
- **Foreground Service**: Keeps VRChat notifications and chatbox running in the background
- **Notification Access**: Reads music notifications to display "Now Playing" in your chatbox
- **Internet**: Connects to VRChat's WebSocket, Firebase, Discord, and GitHub for updates
- **Install Packages**: Allows in-app updates downloaded from GitHub Releases
- **Overlay** (optional): Floating controls over other apps

## 5. Risks You Should Know

**VRChat:** VRC-A uses VRChat's public API and WebSocket pipeline. While it does not modify game files or inject code, (VRChat) could change their API or terms at any time. We are not responsible for any action VRChat takes on your account.

**Discord RPC:** The Discord integration runs a real Discord web session in a hidden WebView and injects presence data onto the gateway connection. This is **not an approved Discord integration**. Risks include:
- Discord may detect and terminate the session
- Disconnecting improperly could invalidate sessions on your other devices
- Your Discord account could be flagged or restricted

You are shown a one-time risk acknowledgment before enabling this feature.

**Updates:** In-app updates download APKs. The app verifies downloads are valid before prompting install, but you are responsible for reviewing what you install.

## 6. Acceptable Use
Do not:
- Harass, spam, or harm others using by this app
- Bypass moderation restrictions
- Exploit or interfere with the service
- Violate VRChat's or Discord's Terms of Service

## 7. Moderation
Administrators may warn, restrict, or permanently ban users at their discretion. The app can be remotely disabled on your device. Moderation decisions are final.

## 8. No Warranty
VRC-A is provided "as is." We do not guarantee uptime, compatibility, or that third-party services (VRChat, Discord, Firebase) will continue to work with this app. We are not liable for data loss, account actions, or service interruptions.

## 9. Changes
These terms may update at any time. Continued use means you accept the changes.

## 10. Contact
Reach the developer through the discord support server https://discord.com/invite/Vaj7h4qCey
