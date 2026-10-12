#!/usr/bin/env bash
# Runs the core-logic + role-access test suite against a throwaway database.
set -e
cd "$(dirname "$0")"
javac -cp "lib/*" -d bin $(find backend/src -name '*.java')
java -cp "bin:lib/*" placeiq.Tests
