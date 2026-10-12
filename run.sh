#!/usr/bin/env bash
# PlaceIQ - build and run. Java 11+ only; the SQLite and Gson jars are already in lib/.
set -e
cd "$(dirname "$0")"
echo "Compiling Java backend..."
javac -cp "lib/*" -d bin $(find backend/src -name '*.java')
echo "Starting PlaceIQ on http://localhost:8080"
exec java -cp "bin:lib/*" placeiq.Main "$@"
