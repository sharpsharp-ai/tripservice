#!/bin/bash
# Zeigt je Klasse die Zeilen, die die Tests nicht oder nur teilweise erreichen. Vorher: mvn -q verify
# Aufruf: scripts/unabgedeckt.sh [Klassenname]   (ohne Argument: alle Klassen)
set -u
dir=target/site/jacoco
[ -d "$dir" ] || { echo "Kein Bericht. Erst mvn -q verify."; exit 1; }
for html in "$dir"/*/*.java.html; do
  klasse=$(basename "$html" .java.html)
  [ $# -gt 0 ] && [ "$klasse" != "$1" ] && continue
  echo "== $klasse"
  grep -o '<span class="[a-z ]*" id="L[0-9]*"[^>]*>[^<]*' "$html" \
    | grep -E 'class="(nc|[a-z]c b[pn]c)' \
    | sed -E 's/<span class="nc" id="L([0-9]+)">(.*)/Zeile \1, nie erreicht: \2/; s/<span class="[a-z]c b[pn]c" id="L([0-9]+)" title="([^"]*)">(.*)/Zeile \1, \2: \3/' \
    | sed -E 's/([0-9]+) of ([0-9]+) branches missed\./\1 von \2 Zweigen nicht erreicht/; s/All ([0-9]+) branches missed\./alle \1 Zweige nicht erreicht/; s/: +/: /' \
    | sed -e 's/&quot;/"/g; s/&lt;/</g; s/&gt;/>/g; s/&amp;/\&/g'
  awk -F, -v k="$klasse" '$3==k {print "Zusammenfassung: Zweige nicht erreicht " $6 ", erreicht " $7 "; Zeilen nicht erreicht " $8 ", erreicht " $9}' "$dir/jacoco.csv"
done
