.PHONY: build dev-install dev-run test clean

LG ?= /Users/andrew/Projects/let-go/lg
LGX ?= "LGX_LG=/Users/andrew/Projects/let-go/lg lgx"
BIN := bin/tiny-cli

# Styling for output
YELLOW := "\e[1;33m"
NC := "\e[0m"
INFO := @sh -c '\
    printf $(YELLOW); \
    echo "=> $$1"; \
    printf $(NC)' VALUE

# Ignore output of make `echo` command
.SILENT:

.PHONY: help  # Show list of targets with descriptions
help:
	@$(INFO) "Commands:"
	@grep '^.PHONY: .* #' Makefile | sed 's/\.PHONY: \(.*\) # \(.*\)/\1 > \2/' | column -tx -s ">"

.PHONY: build  # Build binary
build:
	@$(INFO) "Building $(BIN)..."
	@mkdir -p $(dir $(BIN))
	@LGX_LG=/Users/andrew/Projects/let-go/lg lgx run -b $(BIN) src/tiny_cli/core.cljc
	@echo "built $(BIN)"

.PHONY: dev-run  # Run development script
dev-run:
	@$(INFO) "Running development script..."
	@LGX_LG=/Users/andrew/Projects/let-go/lg lgx run src/tiny_cli/core.cljc

.PHONY: test  # Run tests
test:
	@$(INFO) "Running tests..."
	bash test/run.sh

.PHONY: fmt  # Format code
fmt:
	@$(INFO) "Formatting code..."
	cljfmt fix

.PHONY: fmt-check  # Check code formatting
fmt-check:
	@$(INFO) "Checking code formatting..."
	cljfmt check

.PHONY: clean  # Clean build artifacts
clean:
	@$(INFO) "Cleaning build artifacts..."
	rm -rf $(dir $(BIN))
