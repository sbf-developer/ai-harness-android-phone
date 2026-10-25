# Paste this into the supported Termux app after opening it once.
# Only the named external-app setting is replaced; other Termux settings are retained.
pkg update -y &&
pkg install -y python git openssh ripgrep coreutils &&
mkdir -p "$HOME/.termux" "$HOME/projects" &&
touch "$HOME/.termux/termux.properties" &&
sed -i '/^[[:space:]]*allow-external-apps[[:space:]]*=/d' "$HOME/.termux/termux.properties" &&
printf '\nallow-external-apps=true\n' >> "$HOME/.termux/termux.properties" &&
termux-reload-settings
