package dev.aryan.nitagent.tools;

import java.io.*;
import java.nio.file.*;
import org.springframework.stereotype.Component;

@Component
public class FileReaderTool {

    public String read(String filePath) {
        try {
            return Files.readString(Path.of(filePath));
        } catch (IOException e) {return "Error reading file: " + e.getMessage();}
    }
}