import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    private static final List<String> BUILTINS = Arrays.asList("echo", "exit", "type", "pwd", "cd", "complete", "jobs");

    private static Path currentDirectory =
            Paths.get(System.getProperty("user.dir")).toAbsolutePath();

    private static final Map<String, String> completionSpecs = new HashMap<>();

    private static int jobCounter = 0;

    private static class Job {
        final int number;
        final long pid;
        final String command; // without trailing " &"
        final Process process;
        String status = "Running";
        Job(int number, long pid, String command, Process process) {
            this.number = number; this.pid = pid; this.command = command; this.process = process;
        }
    }

    private static final List<Job> jobList = new ArrayList<>();

    public static void main(String[] args) throws Exception {

        enableRawMode();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { disableRawMode(); } catch (Exception ignored) {}
        }));

        InputStream stdin = System.in;

        while (true) {

            reapJobs(false); // check for finished jobs before each prompt
            System.out.print("$ ");
            System.out.flush();

            String input = readLine(stdin);
            if (input == null) break;

            input = input.trim();

            List<String> parts = parseArguments(input);

            if (parts.isEmpty()) continue;

            if (parts.get(0).equals("exit")) break;

            // Pipeline: split at '|'.
            if (parts.contains("|")) {
                List<List<String>> segments = new ArrayList<>();
                List<String> seg = new ArrayList<>();
                for (String p : parts) {
                    if (p.equals("|")) { if (!seg.isEmpty()) { segments.add(seg); seg = new ArrayList<>(); } }
                    else seg.add(p);
                }
                if (!seg.isEmpty()) segments.add(seg);

                if (segments.size() >= 2) {
                    boolean anyBuiltin = false;
                    for (List<String> s : segments) if (BUILTINS.contains(s.get(0))) { anyBuiltin = true; break; }

                    if (!anyBuiltin) {
                        // All-external fast path: OS-level pipes via startPipeline.
                        List<ProcessBuilder> builders = new ArrayList<>();
                        boolean valid = true;
                        for (List<String> s : segments) {
                            Path exec = findExecutable(s.get(0));
                            if (exec == null) { rawPrintln(s.get(0) + ": command not found"); valid = false; break; }
                            ProcessBuilder pb = new ProcessBuilder(s).directory(currentDirectory.toFile());
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                            builders.add(pb);
                        }
                        if (valid) {
                            try {
                                List<Process> procs = ProcessBuilder.startPipeline(builders);
                                Process last = procs.get(procs.size() - 1);
                                BufferedReader out = new BufferedReader(new InputStreamReader(last.getInputStream()));
                                String ln;
                                while ((ln = out.readLine()) != null) rawPrintln(ln);
                                for (Process pr : procs) pr.waitFor();
                            } catch (Exception e) {
                                rawPrintln("pipeline error: " + e.getMessage());
                            }
                        }
                    } else {
                        // Mixed pipeline: step through segments, buffering output between each.
                        java.io.ByteArrayInputStream curIn =
                                new java.io.ByteArrayInputStream(new byte[0]);
                        boolean broken = false;
                        for (int si = 0; si < segments.size() && !broken; si++) {
                            List<String> s = segments.get(si);
                            boolean isLast = (si == segments.size() - 1);
                            java.io.ByteArrayOutputStream capOut = new java.io.ByteArrayOutputStream();

                            if (BUILTINS.contains(s.get(0))) {
                                runBuiltinInPipeline(s, curIn, new java.io.PrintStream(capOut));
                            } else {
                                Path exec = findExecutable(s.get(0));
                                if (exec == null) { rawPrintln(s.get(0) + ": command not found"); broken = true; break; }
                                ProcessBuilder pb = new ProcessBuilder(s).directory(currentDirectory.toFile());
                                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                                Process proc = pb.start();
                                final java.io.ByteArrayInputStream ci = curIn;
                                Thread wt = new Thread(() -> {
                                    try { ci.transferTo(proc.getOutputStream()); proc.getOutputStream().close(); }
                                    catch (Exception ignored) {}
                                });
                                wt.start();
                                try { proc.getInputStream().transferTo(capOut); proc.waitFor(); wt.join(); }
                                catch (Exception ignored) {}
                            }

                            if (isLast) {
                                java.io.BufferedReader br = new java.io.BufferedReader(
                                        new java.io.InputStreamReader(
                                                new java.io.ByteArrayInputStream(capOut.toByteArray())));
                                String ln;
                                try { while ((ln = br.readLine()) != null) rawPrintln(ln); }
                                catch (Exception ignored) {}
                            } else {
                                curIn = new java.io.ByteArrayInputStream(capOut.toByteArray());
                            }
                        }
                    }
                }
                continue;
            }

            String outputFile = null;
            boolean outputAppend = false;
            String errorFile = null;
            boolean errorAppend = false;
            List<String> filteredParts = new ArrayList<>();

            for (int i = 0; i < parts.size(); i++) {
                String part = parts.get(i);
                if ((part.equals(">>") || part.equals("1>>")) && i + 1 < parts.size()) {
                    outputFile = parts.get(i + 1);
                    outputAppend = true;
                    i++;
                } else if ((part.equals(">") || part.equals("1>")) && i + 1 < parts.size()) {
                    outputFile = parts.get(i + 1);
                    outputAppend = false;
                    i++;
                } else if (part.equals("2>>") && i + 1 < parts.size()) {
                    errorFile = parts.get(i + 1);
                    errorAppend = true;
                    i++;
                } else if (part.equals("2>") && i + 1 < parts.size()) {
                    errorFile = parts.get(i + 1);
                    errorAppend = false;
                    i++;
                } else {
                    filteredParts.add(part);
                }
            }
            parts = filteredParts;

            boolean background = false;
            if (!parts.isEmpty() && parts.get(parts.size() - 1).equals("&")) {
                background = true;
                parts.remove(parts.size() - 1);
            }

            if (errorFile != null && !errorAppend) {
                new FileWriter(errorFile, false).close();
            }

            if (parts.get(0).equals("echo")) {

                StringBuilder output = new StringBuilder();
                for (int i = 1; i < parts.size(); i++) {
                    if (i > 1) output.append(" ");
                    output.append(parts.get(i));
                }

                if (outputFile != null) {
                    FileWriter writer = new FileWriter(outputFile, outputAppend);
                    writer.write(output.toString());
                    writer.write(System.lineSeparator());
                    writer.close();
                } else {
                    rawPrintln(output.toString());
                }

            } else if (parts.get(0).equals("pwd")) {

                String output = currentDirectory.toString();

                if (outputFile != null) {
                    FileWriter writer = new FileWriter(outputFile, outputAppend);
                    writer.write(output);
                    writer.write(System.lineSeparator());
                    writer.close();
                } else {
                    rawPrintln(output);
                }

            } else if (parts.get(0).equals("cd")) {

                if (parts.size() > 1) {
                    Path target;

                    if (parts.get(1).equals("~")) {
                        String home = System.getenv("HOME");
                        target = Paths.get(home);
                    } else if (Paths.get(parts.get(1)).isAbsolute()) {
                        target = Paths.get(parts.get(1));
                    } else {
                        target = currentDirectory.resolve(parts.get(1)).normalize();
                    }

                    if (Files.exists(target) && Files.isDirectory(target)) {
                        currentDirectory = target.toAbsolutePath().normalize();
                    } else {
                        rawPrintln("cd: " + parts.get(1) + ": No such file or directory");
                    }
                }

            } else if (parts.get(0).equals("jobs")) {

                reapJobs(true);

            } else if (parts.get(0).equals("complete")) {

                if (parts.size() >= 4 && parts.get(1).equals("-C")) {
                    completionSpecs.put(parts.get(3), parts.get(2));
                } else if (parts.size() >= 3 && parts.get(1).equals("-r")) {
                    completionSpecs.remove(parts.get(2));
                } else if (parts.size() >= 3 && parts.get(1).equals("-p")) {
                    String cmd = parts.get(2);
                    if (completionSpecs.containsKey(cmd)) {
                        rawPrintln("complete -C '" + completionSpecs.get(cmd) + "' " + cmd);
                    } else {
                        rawPrintln("complete: " + cmd + ": no completion specification");
                    }
                }

            } else if (parts.get(0).equals("type")) {

                if (parts.size() < 2) continue;

                String command = parts.get(1);
                String result;

                if (BUILTINS.contains(command)) {
                    result = command + " is a shell builtin";
                } else {
                    Path executable = findExecutable(command);
                    if (executable != null) {
                        result = command + " is " + executable;
                    } else {
                        result = command + ": not found";
                    }
                }

                if (outputFile != null) {
                    FileWriter writer = new FileWriter(outputFile, outputAppend);
                    writer.write(result);
                    writer.write(System.lineSeparator());
                    writer.close();
                } else {
                    rawPrintln(result);
                }

            } else {

                Path executable = findExecutable(parts.get(0));

                if (executable != null) {

                    List<String> command = new ArrayList<>(parts);
                    ProcessBuilder pb = new ProcessBuilder(command)
                            .directory(executable.getParent().toFile());

                    if (outputFile != null) {
                        if (outputAppend) {
                            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(outputFile)));
                        } else {
                            pb.redirectOutput(new File(outputFile));
                        }
                    } else if (background) {
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                    }
                    if (errorFile != null) {
                        if (errorAppend) {
                            pb.redirectError(ProcessBuilder.Redirect.appendTo(new File(errorFile)));
                        } else {
                            pb.redirectError(new File(errorFile));
                        }
                    } else if (background) {
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                    }

                    Process process = pb.start();

                    if (background) {
                        // Assign the smallest available job number.
                        java.util.Set<Integer> used = new java.util.HashSet<>();
                        for (Job j : jobList) used.add(j.number);
                        int jobNum = 1;
                        while (used.contains(jobNum)) jobNum++;
                        String cmd = String.join(" ", parts);
                        jobList.add(new Job(jobNum, process.pid(), cmd, process));
                        rawPrintln("[" + jobNum + "] " + process.pid());
                    } else {
                        String line;

                        if (errorFile == null) {
                            BufferedReader errorReader =
                                    new BufferedReader(new InputStreamReader(process.getErrorStream()));
                            while ((line = errorReader.readLine()) != null) {
                                rawPrintln(line);
                            }
                        }

                        if (outputFile == null) {
                            BufferedReader outputReader =
                                    new BufferedReader(new InputStreamReader(process.getInputStream()));
                            while ((line = outputReader.readLine()) != null) {
                                rawPrintln(line);
                            }
                        }

                        process.waitFor();
                    }

                } else {
                    rawPrintln(parts.get(0) + ": command not found");
                }
            }
        }

        disableRawMode();
    }

    // Runs a builtin command with explicit stdin/stdout streams (used inside pipelines).
    private static void runBuiltinInPipeline(List<String> parts, InputStream stdin, java.io.PrintStream out) {
        String cmd = parts.get(0);
        if (cmd.equals("echo")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i < parts.size(); i++) { if (i > 1) sb.append(" "); sb.append(parts.get(i)); }
            out.print(sb + "\n");
        } else if (cmd.equals("type")) {
            if (parts.size() >= 2) {
                String command = parts.get(1);
                String result;
                if (BUILTINS.contains(command)) {
                    result = command + " is a shell builtin";
                } else {
                    Path executable = findExecutable(command);
                    result = executable != null ? command + " is " + executable : command + ": not found";
                }
                out.print(result + "\n");
            }
        } else if (cmd.equals("pwd")) {
            out.print(currentDirectory + "\n");
        }
        // cd and exit are not meaningful in pipelines; ignore them.
    }

    // showAll=true: display Running and Done (used by `jobs` builtin)
    // showAll=false: display only Done entries (used before each prompt)
    private static void reapJobs(boolean showAll) {
        for (Job job : jobList) {
            if ("Running".equals(job.status) && !job.process.isAlive()) {
                job.status = "Done";
            }
        }
        for (int i = 0; i < jobList.size(); i++) {
            Job job = jobList.get(i);
            if (!showAll && "Running".equals(job.status)) continue;
            String marker;
            if (i == jobList.size() - 1)      marker = "+";
            else if (i == jobList.size() - 2) marker = "-";
            else                              marker = " ";
            String displayCmd = "Running".equals(job.status) ? job.command + " &" : job.command;
            rawPrintln("[" + job.number + "]" + marker + "  "
                    + String.format("%-24s", job.status) + displayCmd);
        }
        jobList.removeIf(job -> "Done".equals(job.status));
    }

    // In raw mode \n alone doesn't reset column; use \r\n for all console output.
    // Prepend \r so background-job bare-\n column drift is corrected before we write.
    private static void rawPrintln(String s) {
        System.out.print("\r" + s + "\r\n");
        System.out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean bellRung = false; // true when last TAB had multiple matches and rang bell
        while (true) {
            int b = in.read();
            if (b == -1) return null;

            if (b == '\t') {
                bellRung = handleTabCompletion(sb, bellRung);
            } else if (b == '\r' || b == '\n') {
                System.out.print("\r\n");
                System.out.flush();
                return sb.toString();
            } else if (b == 127 || b == '\b') {
                bellRung = false;
                if (sb.length() > 0) {
                    sb.deleteCharAt(sb.length() - 1);
                    System.out.print("\b \b");
                    System.out.flush();
                }
            } else if (b >= 32) {
                bellRung = false;
                sb.append((char) b);
                System.out.print((char) b);
                System.out.flush();
            }
        }
    }

    // Returns true if bell was rung for multiple matches (signals caller that next TAB should show list).
    private static boolean handleTabCompletion(StringBuilder current, boolean showList) {
        String partial = current.toString();
        if (partial.isEmpty()) return false;

        // If there is already a space the user is completing a filename argument.
        int lastSpace = partial.lastIndexOf(' ');
        if (lastSpace >= 0) {
            // Check for a registered programmable completer for the command word.
            String cmdWord = partial.substring(0, partial.indexOf(' ')).trim();
            if (!cmdWord.isEmpty() && completionSpecs.containsKey(cmdWord)) {
                try {
                    String scriptPath = completionSpecs.get(cmdWord);
                    // Derive the three context arguments for the completer.
                    String[] allWords = partial.trim().split("\\s+");
                    boolean endsWithSpace = partial.endsWith(" ");
                    String curToken  = endsWithSpace ? "" : allWords[allWords.length - 1];
                    String prevToken;
                    if (endsWithSpace) {
                        prevToken = allWords.length >= 2 ? allWords[allWords.length - 1] : "";
                    } else {
                        prevToken = allWords.length >= 2 ? allWords[allWords.length - 2] : "";
                    }
                    ProcessBuilder pb = new ProcessBuilder(scriptPath, cmdWord, curToken, prevToken);
                    pb.environment().put("COMP_LINE", partial);
                    pb.environment().put("COMP_POINT", String.valueOf(partial.getBytes().length));
                    Process proc = pb.start();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    List<String> lines = new ArrayList<>();
                    String ln;
                    while ((ln = reader.readLine()) != null) {
                        if (!ln.isEmpty()) lines.add(ln);
                    }
                    proc.waitFor();
                    if (lines.size() == 1) {
                        String completion = lines.get(0);
                        String suffix = (completion.startsWith(curToken)
                                ? completion.substring(curToken.length()) : completion) + " ";
                        System.out.print(suffix);
                        System.out.flush();
                        current.append(suffix);
                    } else if (lines.isEmpty()) {
                        System.out.print("\007");
                        System.out.flush();
                    } else {
                        // Multiple candidates — try LCP extension first.
                        String lcp = longestCommonPrefix(lines);
                        if (lcp.length() > curToken.length()) {
                            String suffix = lcp.substring(curToken.length());
                            System.out.print(suffix);
                            System.out.flush();
                            current.append(suffix);
                        } else if (!showList) {
                            System.out.print("\007");
                            System.out.flush();
                            return true;
                        } else {
                            Collections.sort(lines);
                            System.out.print("\r\n");
                            System.out.print(String.join("  ", lines));
                            System.out.print("\r\n$ ");
                            System.out.print(partial);
                            System.out.flush();
                        }
                    }
                } catch (Exception ignored) {}
                return false;
            }

            String token = partial.substring(lastSpace + 1); // e.g. "path/to/f", "re", or ""

            // Split token at last '/' into a directory part and a name prefix.
            String dirPart;
            String namePrefix;
            int lastSlash = token.lastIndexOf('/');
            if (lastSlash >= 0) {
                dirPart   = token.substring(0, lastSlash + 1); // "path/to/"
                namePrefix = token.substring(lastSlash + 1);   // "f"
            } else {
                dirPart   = "";
                namePrefix = token;
            }

            Path searchDir = dirPart.isEmpty()
                    ? currentDirectory
                    : currentDirectory.resolve(dirPart);

            List<File> fileMatches = new ArrayList<>();
            File[] entries = searchDir.toFile().listFiles();
            if (entries != null) {
                for (File f : entries) {
                    if (f.getName().startsWith(namePrefix)) {
                        fileMatches.add(f);
                    }
                }
            }

            if (fileMatches.size() == 1) {
                File match = fileMatches.get(0);
                String trailing = match.isDirectory() ? "/" : " ";
                String suffix = match.getName().substring(namePrefix.length()) + trailing;
                System.out.print(suffix);
                System.out.flush();
                current.append(suffix);
                return false;
            } else if (fileMatches.isEmpty()) {
                System.out.print("\007");
                System.out.flush();
                return false;
            } else {
                // Multiple matches — try LCP extension first.
                List<String> names = new ArrayList<>();
                for (File f : fileMatches) names.add(f.getName());
                String lcp = longestCommonPrefix(names);

                if (lcp.length() > namePrefix.length()) {
                    String suffix = lcp.substring(namePrefix.length());
                    System.out.print(suffix);
                    System.out.flush();
                    current.append(suffix);
                    return false;
                }

                // LCP == namePrefix: no further extension possible.
                if (!showList) {
                    System.out.print("\007");
                    System.out.flush();
                    return true;
                }
                fileMatches.sort((a, b) -> a.getName().compareTo(b.getName()));
                StringBuilder display = new StringBuilder();
                for (int i = 0; i < fileMatches.size(); i++) {
                    if (i > 0) display.append("  ");
                    File f = fileMatches.get(i);
                    display.append(f.getName());
                    if (f.isDirectory()) display.append("/");
                }
                System.out.print("\r\n");
                System.out.print(display);
                System.out.print("\r\n$ ");
                System.out.print(partial);
                System.out.flush();
                return false;
            }
        }

        List<String> matches = new ArrayList<>();
        for (String builtin : BUILTINS) {
            if (builtin.startsWith(partial)) {
                matches.add(builtin);
            }
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File folder = new File(dir);
                if (!folder.isDirectory()) continue;
                File[] files = folder.listFiles();
                if (files == null) continue;
                for (File f : files) {
                    if (f.getName().startsWith(partial) && f.canExecute() && !matches.contains(f.getName())) {
                        matches.add(f.getName());
                    }
                }
            }
        }

        if (matches.size() == 1) {
            String completion = matches.get(0);
            String suffix = completion.substring(partial.length()) + " ";
            System.out.print(suffix);
            System.out.flush();
            current.append(suffix);
            return false;
        } else if (matches.isEmpty()) {
            System.out.print("\007");
            System.out.flush();
            return false;
        } else {
            // Multiple matches — try to extend to the longest common prefix first.
            String lcp = longestCommonPrefix(matches);
            if (lcp.length() > partial.length()) {
                String suffix = lcp.substring(partial.length());
                System.out.print(suffix);
                System.out.flush();
                current.append(suffix);
                return false; // progress made, reset bell state
            }
            // LCP == partial: no further extension possible.
            if (!showList) {
                System.out.print("\007");
                System.out.flush();
                return true;
            } else {
                Collections.sort(matches);
                System.out.print("\r\n");
                System.out.print(String.join("  ", matches));
                System.out.print("\r\n$ ");
                System.out.print(current.toString());
                System.out.flush();
                return false;
            }
        }
    }

    private static String longestCommonPrefix(List<String> strs) {
        if (strs.isEmpty()) return "";
        String prefix = strs.get(0);
        for (int i = 1; i < strs.size(); i++) {
            String s = strs.get(i);
            int j = 0;
            while (j < prefix.length() && j < s.length() && prefix.charAt(j) == s.charAt(j)) {
                j++;
            }
            prefix = prefix.substring(0, j);
        }
        return prefix;
    }

    private static void enableRawMode() throws Exception {
        new ProcessBuilder("stty", "raw", "-echo")
                .inheritIO()
                .start()
                .waitFor();
    }

    private static void disableRawMode() throws Exception {
        new ProcessBuilder("stty", "sane")
                .inheritIO()
                .start()
                .waitFor();
    }

    private static List<String> parseArguments(String input) {

        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;

        for (int i = 0; i < input.length(); i++) {

            char c = input.charAt(i);

            if (inDoubleQuotes && c == '\\') {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                        continue;
                    }
                }
                current.append('\\');
                continue;
            }

            if (!inSingleQuotes && !inDoubleQuotes && c == '\\') {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
                continue;
            }

            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                continue;
            }

            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                continue;
            }

            if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }

    private static Path findExecutable(String command) {

        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;

        String[] directories = pathEnv.split(File.pathSeparator);

        for (String dir : directories) {
            Path candidate = Paths.get(dir, command);
            if (Files.exists(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }

        return null;
    }
}
