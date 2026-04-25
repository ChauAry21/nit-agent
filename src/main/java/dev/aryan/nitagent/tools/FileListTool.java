package dev.aryan.nitagent.tools;

import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import  org.springframework.stereotype.Component;

@Component
public class FileListTool {

    public String list(String directoryPath) {
        try {
            return Files.walk(Path.of(directoryPath))
                    .filter(p -> !p.toString().contains("node_modules"))
                    .filter(p -> !p.toString().contains(".git"))
                    .map(p -> p.toString())
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {return "Error listing files: " + e.getMessage();}
    }
}