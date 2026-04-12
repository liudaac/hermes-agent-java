#!/bin/bash
# Hermes Agent Java Installation Script

set -e

HERMES_VERSION="0.1.0"
HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
INSTALL_DIR="$HERMES_HOME/java"

echo "=== Hermes Agent Java Installer ==="
echo "Version: $HERMES_VERSION"
echo "Install directory: $INSTALL_DIR"
echo

# Check Java version
check_java() {
    if ! command -v java &> /dev/null; then
        echo "Error: Java is not installed"
        echo "Please install Java 21 or later"
        exit 1
    fi
    
    JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "$JAVA_VERSION" -lt "21" ]; then
        echo "Error: Java 21 or later is required (found: $JAVA_VERSION)"
        exit 1
    fi
    
    echo "✓ Java version: $(java -version 2>&1 | head -n 1)"
}

# Check Maven
check_maven() {
    if ! command -v mvn &> /dev/null; then
        echo "Warning: Maven is not installed"
        echo "You can use the pre-built JAR or install Maven"
    else
        echo "✓ Maven version: $(mvn -version | head -n 1)"
    fi
}

# Create directories
setup_dirs() {
    echo "Creating directories..."
    mkdir -p "$INSTALL_DIR"
    mkdir -p "$HERMES_HOME/logs"
    mkdir -p "$HERMES_HOME/skills"
    mkdir -p "$HERMES_HOME/memory"
    echo "✓ Directories created"
}

# Build or copy JAR
install_jar() {
    if [ -f "target/hermes-agent-java-$HERMES_VERSION.jar" ]; then
        echo "Using pre-built JAR..."
        cp "target/hermes-agent-java-$HERMES_VERSION.jar" "$INSTALL_DIR/hermes-agent.jar"
    elif command -v mvn &> /dev/null; then
        echo "Building from source..."
        mvn clean package -DskipTests
        cp "target/hermes-agent-java-$HERMES_VERSION.jar" "$INSTALL_DIR/hermes-agent.jar"
    else
        echo "Error: No JAR file found and Maven is not installed"
        exit 1
    fi
    echo "✓ JAR installed to $INSTALL_DIR/hermes-agent.jar"
}

# Create wrapper script
create_wrapper() {
    WRAPPER="$HERMES_HOME/bin/hermes"
    mkdir -p "$HERMES_HOME/bin"
    
    cat > "$WRAPPER" << 'EOF'
#!/bin/bash
# Hermes Agent Java Wrapper

HERMES_HOME="${HERMES_HOME:-$HOME/.hermes}"
JAR="$HERMES_HOME/java/hermes-agent.jar"

if [ ! -f "$JAR" ]; then
    echo "Error: Hermes Agent JAR not found at $JAR"
    exit 1
fi

# Set default memory options
JAVA_OPTS="${HERMES_JAVA_OPTS:--Xmx2g -XX:+UseG1GC}"

# Run Hermes
exec java $JAVA_OPTS -jar "$JAR" "$@"
EOF
    
    chmod +x "$WRAPPER"
    echo "✓ Wrapper script created at $WRAPPER"
}

# Create default config
create_config() {
    CONFIG="$HERMES_HOME/config.yaml"
    if [ ! -f "$CONFIG" ]; then
        cat > "$CONFIG" << EOF
model:
  provider: openrouter
  model: anthropic/claude-3.5-sonnet
  # api_key: Set via OPENROUTER_API_KEY env var

agent:
  max_turns: 90
  gateway_timeout: 300

tools:
  enabled:
    - web_search
    - terminal
    - file_operations
EOF
        echo "✓ Default config created at $CONFIG"
    else
        echo "✓ Config already exists at $CONFIG"
    fi
}

# Add to PATH
add_to_path() {
    SHELL_RC=""
    if [ -n "$BASH_VERSION" ]; then
        SHELL_RC="$HOME/.bashrc"
    elif [ -n "$ZSH_VERSION" ]; then
        SHELL_RC="$HOME/.zshrc"
    fi
    
    if [ -n "$SHELL_RC" ] && [ -f "$SHELL_RC" ]; then
        if ! grep -q "$HERMES_HOME/bin" "$SHELL_RC"; then
            echo "" >> "$SHELL_RC"
            echo "# Hermes Agent" >> "$SHELL_RC"
            echo 'export PATH="$HOME/.hermes/bin:$PATH"' >> "$SHELL_RC"
            echo "✓ Added to PATH in $SHELL_RC"
            echo "  Run 'source $SHELL_RC' to apply changes"
        else
            echo "✓ Already in PATH"
        fi
    fi
}

# Main installation
main() {
    check_java
    check_maven
    setup_dirs
    install_jar
    create_wrapper
    create_config
    add_to_path
    
    echo
    echo "=== Installation Complete ==="
    echo
    echo "Usage:"
    echo "  $HERMES_HOME/bin/hermes           # Start interactive chat"
    echo "  $HERMES_HOME/bin/hermes gateway   # Start gateway"
    echo
    echo "Or add $HERMES_HOME/bin to your PATH and run:"
    echo "  hermes"
    echo
    echo "Configuration: $HERMES_HOME/config.yaml"
    echo
    echo "Set your API key:"
    echo "  export OPENROUTER_API_KEY='your-key-here'"
    echo
}

main "$@"
