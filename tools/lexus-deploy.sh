#!/usr/bin/env bash
#
# Deploie target/traccar-release.tar.gz sur un serveur et redemarre Traccar.
#
#   tools/lexus-deploy.sh <numero du serveur> <dossier de Traccar sur le serveur>
#
# Lit SSH_PRIVATE_KEY (base64) et SERVER_<n>_IP / _PORT / _USER.
# Sur le serveur : sauvegarde horodatee du jar et de lib/, remplacement,
# redemarrage, puis retour automatique a la sauvegarde si le service ne tient pas.

set -euo pipefail

N="${1:?numero du serveur manquant}"
DIR="${2:?dossier de Traccar manquant}"
RELEASE="target/traccar-release.tar.gz"

ip_var="SERVER_${N}_IP"; port_var="SERVER_${N}_PORT"; user_var="SERVER_${N}_USER"
IP="${!ip_var:?variable $ip_var manquante}"
PORT="${!port_var:?variable $port_var manquante}"
USER_NAME="${!user_var:?variable $user_var manquante}"
: "${SSH_PRIVATE_KEY:?variable SSH_PRIVATE_KEY manquante}"

[ -f "$RELEASE" ] || { echo "$RELEASE introuvable : l'etape de construction n'a pas tourne"; exit 1; }

if ! command -v ssh >/dev/null 2>&1; then
    SUDO=""; [ "$(id -u)" -eq 0 ] || SUDO="sudo"
    $SUDO apt-get update -qq && $SUDO apt-get install -y -qq openssh-client >/dev/null
fi

mkdir -p ~/.ssh && chmod 700 ~/.ssh
trap 'rm -f ~/.ssh/id_deploy' EXIT
# la cle n'est jamais affichee dans les journaux
( umask 077; echo "$SSH_PRIVATE_KEY" | base64 -d > ~/.ssh/id_deploy )
ssh-keyscan -p "$PORT" "$IP" >> ~/.ssh/known_hosts 2>/dev/null

SSH=(ssh -i ~/.ssh/id_deploy -p "$PORT" "$USER_NAME@$IP")

echo "Deploiement sur S$N ($DIR)"
scp -q -i ~/.ssh/id_deploy -P "$PORT" "$RELEASE" "$USER_NAME@$IP:/tmp/traccar-release.tar.gz"

"${SSH[@]}" "DIR='$DIR' bash -s" <<'REMOTE'
set -euo pipefail

if ! java -version 2>&1 | grep -Eq 'version "(2[1-9]|[3-9][0-9])'; then
    echo "Java 21 ou plus recent introuvable sur ce serveur : deploiement annule, rien n'a ete modifie."
    java -version 2>&1 | head -1 || true
    rm -f /tmp/traccar-release.tar.gz
    exit 1
fi

STAMP=$(date +%F-%H-%M)
BACKUP="$DIR/backups/traccar-$STAMP.tar.gz"
sudo mkdir -p "$DIR/backups"

# sauvegarde de ce qui va etre remplace (jar, lib, schema, templates s'ils existent)
EXISTING=()
for item in tracker-server.jar lib schema templates; do
    [ -e "$DIR/$item" ] && EXISTING+=("$item")
done
if [ ${#EXISTING[@]} -gt 0 ]; then
    sudo tar -czf "$BACKUP" -C "$DIR" "${EXISTING[@]}"
    echo "Sauvegarde : $BACKUP"
fi

install_release() {
    sudo rm -rf "$DIR/lib" "$DIR/schema" "$DIR/templates"
    sudo tar -xzf "$1" -C "$DIR"
}

install_release /tmp/traccar-release.tar.gz
rm -f /tmp/traccar-release.tar.gz
sudo systemctl restart traccar

# le service doit encore tourner apres 30 secondes
sleep 30
if sudo systemctl is-active --quiet traccar; then
    echo "Traccar redemarre et actif."
else
    echo "Traccar ne demarre pas avec la nouvelle version."
    sudo journalctl -u traccar -n 30 --no-pager || true
    if [ -f "$BACKUP" ]; then
        echo "Retour a la sauvegarde $BACKUP"
        install_release "$BACKUP"
        sudo systemctl restart traccar
    fi
    exit 1
fi
REMOTE

rm -f ~/.ssh/id_deploy
echo "S$N : termine."
