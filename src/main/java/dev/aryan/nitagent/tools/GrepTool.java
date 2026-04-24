package dev.aryan.nitagent.tools;

import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.*;
import org.springframework.stereotype.Component;

@Component
public class GrepTool {

    public String grep(String pattern, String directoryPath) {
        try {
            return Files.walk(Path.of(directoryPath))
                    .filter(Files::isRegularFile)
                    .flatMap(file -> {
                        try {
                            return Files.lines(file)
                                    .filter(line -> line.contains(pattern))
                                    .map(line -> file + ": " + line);
                        } catch (IOException e) {return java.util.stream.Stream.empty();}
                    })
                    .collect(Collectors.joining("\n"));
        } catch (IOException e) {return "Error running grep: " + e.getMessage();}
    }
}