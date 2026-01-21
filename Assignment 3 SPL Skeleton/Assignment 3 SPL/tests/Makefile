CXX := g++
CXXFLAGS := -std=c++17 -Wall -Wextra -I../client/include
LDFLAGS := -pthread
TARGET := run_tests
BUILD_DIR := build

.PHONY: all clean

all: $(TARGET)

$(TARGET): $(BUILD_DIR)/test_runner.o $(BUILD_DIR)/StompProtocol.o $(BUILD_DIR)/event.o
	$(CXX) $^ -o $@ $(LDFLAGS)

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(BUILD_DIR)/test_runner.o: test_runner.cpp | $(BUILD_DIR)
	$(CXX) $(CXXFLAGS) -I../client/include -c $< -o $@

$(BUILD_DIR)/StompProtocol.o: ../client/src/StompProtocol.cpp | $(BUILD_DIR)
	$(CXX) $(CXXFLAGS) -c $< -o $@

$(BUILD_DIR)/event.o: ../client/src/event.cpp | $(BUILD_DIR)
	$(CXX) $(CXXFLAGS) -c $< -o $@

clean:
	rm -rf $(BUILD_DIR) $(TARGET)
