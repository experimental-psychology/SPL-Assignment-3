CXX = g++
CXXFLAGS = -std=c++11 -Wall -Weffc++ -pthread -g
LDFLAGS = -lboost_system -lpthread

# Directories
SRC_DIR = src
INCLUDE_DIR = include
BIN_DIR = bin
OBJ_DIR = obj

# Source and object files
SOURCES = $(SRC_DIR)/StompClient.cpp $(SRC_DIR)/ConnectionHandler.cpp $(SRC_DIR)/event.cpp
OBJECTS = $(patsubst $(SRC_DIR)/%.cpp, $(OBJ_DIR)/%.o, $(SOURCES))
TARGET = $(BIN_DIR)/StompWCIClient

# Include paths
CXXFLAGS += -I$(INCLUDE_DIR)

# Default target
all: $(TARGET)

# Create directories
$(OBJ_DIR):
	mkdir -p $(OBJ_DIR)

$(BIN_DIR):
	mkdir -p $(BIN_DIR)

# Compile source files
$(OBJ_DIR)/%.o: $(SRC_DIR)/%.cpp | $(OBJ_DIR)
	$(CXX) $(CXXFLAGS) -c $< -o $@

# Link the executable
$(TARGET): $(OBJECTS) | $(BIN_DIR)
	$(CXX) $(LDFLAGS) $^ -o $@

# Clean build artifacts
clean:
	rm -rf $(OBJ_DIR) $(BIN_DIR)

# Phony targets
.PHONY: all clean