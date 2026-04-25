package dev.aryan.nitagent.tools;

import java.io.*;
import java.nio.file.*;
import org.springframework.stereotype.Component;

@Component
public class TestMakerTool {
    public String readForTesting(String filePath) {
        try {
            return Files.readString(Path.of(filePath));
        } catch (IOException e) {return "Error reading file for test generation: " + e.getMessage();}
    }
}
