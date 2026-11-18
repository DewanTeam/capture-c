#!/bin/bash
cd "$(dirname "$0")"

# Default Gradle distribution version
EXT="zip"
DEFAULT_VERSION="8.5"

# Determine the OS
OS="$(uname -s)"
CASEOS="$(echo "$OS" | grep -o '^\(Mingw\|MSYS\|CYGWIN\)')"

# Find the Gradle distribution
GRADLE_USER_HOME="\$(cd "$HOME/.gradle" && pwd)"

# Find Gradle wrapper
if [ -n "$GRADLE_OPTS" ]; then
  EXECUTABLE="\$@"
else
  EXECUTABLE="\$@"
fi

\$GRADLE_USER_HOME/bin/gradle "\$@"
