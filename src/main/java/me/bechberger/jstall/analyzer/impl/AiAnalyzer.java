package me.bechberger.jstall.analyzer.impl;

import me.bechberger.jstall.analyzer.AnalyzerResult;
import me.bechberger.jstall.analyzer.BaseAnalyzer;
import me.bechberger.jstall.analyzer.DumpRequirement;
import me.bechberger.jstall.analyzer.ResolvedData;
import me.bechberger.jstall.provider.requirement.DataRequirements;
import me.bechberger.jstall.util.llm.AiTools;
import me.bechberger.jstall.util.llm.LlmProvider;
import me.bechberger.jstall.util.llm.OpenAiLlmProvider;
import me.bechberger.jstall.util.llm.ToolDefinition;
import me.bechberger.jstall.util.llm.ToolExecutor;
import me.bechberger.jstall.util.SystemAnalyzer;
import me.bechberger.jstall.util.CommandExecutor;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI-powered thread dump analyzer using LLM.
 * <p>
 * Runs StatusAnalyzer to get thread dump analysis, then sends it to an LLM
 * for intelligent insights and answers.
 */
public class AiAnalyzer extends BaseAnalyzer {

    private static final String SYSTEM_PROMPT =
        "You're a helpful thread dump analyzer that likes to be on the point. Given the following thread dump analysis, answer the user's question.";

    private static final String DEFAULT_USER_PROMPT =
        "Summarize the current state of the application. State any potential issues found in the thread dumps." +
        "Don't offer generic advice; focus on the specific findings from the analyses. But start with a short summary of the overall state.\n";

    private final LlmProvider llmProvider;
    private final CommandExecutor commandExecutor;

    public AiAnalyzer(LlmProvider llmProvider) {
        this(llmProvider, new CommandExecutor.LocalCommandExecutor());
    }

    public AiAnalyzer(LlmProvider llmProvider, CommandExecutor commandExecutor) {
        this.llmProvider = llmProvider;
        this.commandExecutor = commandExecutor;
    }

    @Override
    public String name() {
        return "ai";
    }

    @Override
    public Set<String> supportedOptions() {
        // Support all status options plus AI-specific ones
        Set<String> options = new java.util.HashSet<>(new StatusAnalyzer().supportedOptions());
        options.add("model");
        options.add("question");
        options.add("raw");
        options.add("dry-run");
        options.add("short");
        options.add("think");
        options.add("tools");
        options.add("no-tools");
        return options;
    }

    @Override
    public DumpRequirement dumpRequirement() {
        return DumpRequirement.MANY;
    }

    @Override
    public DataRequirements getDataRequirements(Map<String, Object> options) {
        return new StatusAnalyzer().getDataRequirements(options);
    }

    @Override
    public AnalyzerResult analyze(ResolvedData data, Map<String, Object> options) {
        // Extract AI-specific options
        String model = getStringOption(options, "model", "gpt-50-nano");
        String customQuestion = getStringOption(options, "question", null);
        boolean rawOutput = getBooleanOption(options, "raw", false);
        boolean dryRun = getBooleanOption(options, "dry-run", false);
        boolean shortMode = getBooleanOption(options, "short", false);
        boolean showThinking = getBooleanOption(options, "think", false);
        // Auto-enable tools for local OpenAI provider unless explicitly disabled
        boolean useTools;
        if (getBooleanOption(options, "no-tools", false)) {
            useTools = false;
        } else if (options.containsKey("tools")) {
            useTools = getBooleanOption(options, "tools", false);
        } else {
            useTools = (llmProvider instanceof OpenAiLlmProvider);
        }

        // Enable intelligent filtering by default
        Map<String, Object> statusOptions = new HashMap<>(options);

        // Run status analyzer to get thread dump analysis
        StatusAnalyzer statusAnalyzer = new StatusAnalyzer();
        AnalyzerResult statusResult = statusAnalyzer.analyze(data, statusOptions);

        if (statusResult.exitCode() != 0 && statusResult.exitCode() != 2) {
            // Status analyzer failed (but deadlocks are ok - exitCode 2)
            return AnalyzerResult.withExitCode(
                "Failed to analyze thread dumps: " + statusResult.output(),
                1
            );
        }

        String analysis = statusResult.output();

        // Build prompts
        String userPrompt = buildUserPrompt(analysis, customQuestion);

        // Dry-run mode: just print the prompt without calling the API
        if (dryRun) {
            String output = "=== DRY RUN MODE ===\n\n" +
                            "Model: " + model + "\n\n" +
                            "System Prompt:\n" +
                            SYSTEM_PROMPT + "\n\n" +
                            "User Prompt:\n" +
                            userPrompt + "\n" +
                            "\n=== END DRY RUN ===\n";
            return AnalyzerResult.ok(output);
        }

        // Call LLM API
        try {
            String aiAnalysis = callLLM(model, userPrompt, rawOutput, showThinking, useTools, shortMode, data);

            // If short mode, run through LLM again for succinct summary
            if (shortMode && !rawOutput) {
                aiAnalysis = createShortSummary(model, aiAnalysis, false, showThinking);
            }
            if (llmProvider.supportsStreaming()) {
                return AnalyzerResult.ok(""); // Output already printed during streaming
            }
            return AnalyzerResult.ok(aiAnalysis);

        } catch (LlmProvider.LlmException e) {
            if (e.isAuthError()) {
                return AnalyzerResult.withExitCode(
                    "Authentication failed: " + e.getMessage() + "\nPlease check your API key.",
                    4
                );
            } else {
                return AnalyzerResult.withExitCode(
                    "LLM error: " + e.getMessage(),
                    5
                );
            }
        } catch (IOException e) {
            return AnalyzerResult.withExitCode(
                "Network error: " + e.getMessage(),
                3
            );
        }
    }

    private String buildUserPrompt(String analysis, String customQuestion) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Thread Dump Analysis:\n");
        prompt.append("---\n");
        prompt.append(analysis);
        prompt.append("\n---\n\n");
        prompt.append(DEFAULT_USER_PROMPT);

        // Add provider-specific instructions
        String additionalInstructions = llmProvider.getAdditionalInstructions();
        if (!additionalInstructions.isEmpty()) {
            prompt.append("\nAdditional Instructions:\n");
            prompt.append(additionalInstructions);
            prompt.append("\n");
        }

        if (customQuestion != null && !customQuestion.trim().isEmpty()) {
            prompt.append("\n\nUser's specific question: ");
            prompt.append(customQuestion.trim());
        }

        return prompt.toString();
    }

    /**
     * Analyzes all JVMs on the system (full mode).
     *
     * @param count Number of dumps per JVM
     * @param intervalMs Interval between dumps in milliseconds
     * @param options Analysis options
     * @return Analyzer result with AI analysis of the entire system
     */
    public AnalyzerResult analyzeFullSystem(int count, long intervalMs, Map<String, Object> options) {
        // Extract AI-specific options
        String model = getStringOption(options, "model", "gpt-50-nano");
        String customQuestion = getStringOption(options, "question", null);
        boolean rawOutput = getBooleanOption(options, "raw", false);
        boolean dryRun = getBooleanOption(options, "dry-run", false);
        boolean shortMode = getBooleanOption(options, "short", false);
        boolean showThinking = getBooleanOption(options, "think", false);
        double cpuThreshold = getDoubleOption(options, "cpu-threshold", 1.0);

        // Enable intelligent filtering by default
        Map<String, Object> statusOptions = new HashMap<>(options);
        if (!statusOptions.containsKey("intelligent-filter")) {
            statusOptions.put("intelligent-filter", true);
        }

        // Analyze all JVMs
        SystemAnalyzer systemAnalyzer = new SystemAnalyzer(commandExecutor);
        List<SystemAnalyzer.JVMAnalysis> analyses;
        try {
            analyses = systemAnalyzer.analyzeAllJVMs(count, intervalMs, statusOptions, cpuThreshold);
        } catch (IOException e) {
            return AnalyzerResult.withExitCode(
                "Failed to analyze JVMs: " + e.getMessage(),
                1
            );
        }

        if (analyses.isEmpty()) {
            return AnalyzerResult.ok("No active JVMs found (CPU threshold: " + cpuThreshold + "%)");
        }

        System.err.println("Found " + analyses.size() + " active JVM(s)");
        System.err.println();

        // Build combined analysis
        String systemSummary = SystemAnalyzer.formatSystemSummary(analyses);
        String detailedAnalyses = SystemAnalyzer.formatDetailedAnalyses(analyses);

        // Build user prompt with system context
        String userPrompt = buildFullSystemPrompt(systemSummary, detailedAnalyses, customQuestion);

        // Dry-run mode: just print the prompt without calling the API
        if (dryRun) {
            String output = "=== DRY RUN MODE (FULL SYSTEM) ===\n\n" +
                            "Model: " + model + "\n\n" +
                            "System Prompt:\n" +
                            SYSTEM_PROMPT + "\n\n" +
                            "User Prompt:\n" +
                            userPrompt + "\n" +
                            "\n=== END DRY RUN ===\n";
            return AnalyzerResult.ok(output);
        }

        // Call LLM API
        try {
            String aiAnalysis = callLLM(model, userPrompt, rawOutput, showThinking, false, shortMode, null);

            // If short mode, run through LLM again for succinct summary
            if (shortMode && !rawOutput) {
                aiAnalysis = createShortSummary(model, aiAnalysis, true, showThinking);
            }

            return AnalyzerResult.ok(aiAnalysis);

        } catch (LlmProvider.LlmException e) {
            if (e.isAuthError()) {
                return AnalyzerResult.withExitCode(
                    "Authentication failed: " + e.getMessage() + "\nPlease check your API key.",
                    4
                );
            } else {
                return AnalyzerResult.withExitCode(
                    "LLM error: " + e.getMessage(),
                    5
                );
            }
        } catch (IOException e) {
            return AnalyzerResult.withExitCode(
                "Network error: " + e.getMessage(),
                3
            );
        }
    }

    private String buildFullSystemPrompt(String systemSummary, String detailedAnalyses, String customQuestion) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("System-wide Analysis of All JVM Applications:\n\n");
        prompt.append(systemSummary);
        prompt.append("\n\nDetailed Analysis of Each JVM:\n");
        prompt.append("---\n");
        prompt.append(detailedAnalyses);
        prompt.append("\n---\n\n");
        prompt.append("Please provide your analysis in the following structure:\n\n");
        prompt.append("1. Start with a high-level summary of the overall system state and bottom line takewaways\n");
        prompt.append("2. Identify any cross-JVM issues, bottlenecks, or interesting patterns\n");
        prompt.append("3. For each JVM provide a short analysis\n");
        prompt.append("Don't offer generic advice; focus on the specific findings from the analyses.\n");
        prompt.append("This is non-interactive; provide a complete answer based on the data provided. Omit calls for user input.\n");
        prompt.append("Give the JVMs nicer names, but omit sentences like 'Here is a concise, findings-focused analysis of the 5 active JVMs, with nicer names and non-generic guidance.'" +
                      " and omit trying to explain what any JVM is doing in general. Just use 'name (pid)'.\n");
        prompt.append("Don't use JVM1, ..., but 'name (pid)'.\n");
        prompt.append("Every fact needs to be based on the data provided; do not make up any facts or invent any findings" +
                      "and mention short reasons for every statement, be as specific as possible.\n");
        prompt.append("Be succinct and to the point, the user is focused on performance.\n");

        // Add provider-specific instructions
        String additionalInstructions = llmProvider.getAdditionalInstructions();
        if (!additionalInstructions.isEmpty()) {
            prompt.append("\nAdditional Instructions:\n");
            prompt.append(additionalInstructions);
            prompt.append("\n");
        }

        if (customQuestion != null && !customQuestion.trim().isEmpty()) {
            prompt.append("\n\nUser's specific question: ");
            prompt.append(customQuestion.trim());
        }

        return prompt.toString();
    }

    private double getDoubleOption(Map<String, Object> options, String key, double defaultValue) {
        Object value = options.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static final String TOOLS_SYSTEM_ADDENDUM =
        "\n\nYou have tools to dig deeper into the application state. " +
        "Before answering, use tools to verify your hypotheses and gather evidence. " +
        "For example:\n" +
        "- Use get_thread_stack_trace to see exactly what a suspicious thread is doing\n" +
        "- Use search_stack_frames to find threads executing specific code\n" +
        "- Use get_lock_info to verify lock contention\n" +
        "- Use get_top_cpu_threads to identify hot threads\n" +
        "Don't guess — investigate with tools first, then provide a well-supported analysis.";

    private String callLLM(String model, String userPrompt, boolean rawOutput, boolean showThinking,
                           boolean useTools, boolean shortMode, ResolvedData data)
            throws IOException, LlmProvider.LlmException {

        List<LlmProvider.Message> messages = new ArrayList<>();
        messages.add(new LlmProvider.Message("system", SYSTEM_PROMPT +
            (useTools ? TOOLS_SYSTEM_ADDENDUM : "")));
        messages.add(new LlmProvider.Message("user", userPrompt));

        // Use tool-calling loop if enabled and provider supports it
        if (useTools && !rawOutput && llmProvider instanceof OpenAiLlmProvider openAiProvider && data != null) {
            AiTools aiTools = new AiTools(data);
            List<ToolDefinition> tools = aiTools.getToolDefinitions();
            ToolExecutor executor = aiTools.createExecutor();

            StringBuilder output = new StringBuilder();
            LlmProvider.StreamHandlers handlers = new LlmProvider.StreamHandlers(
                content -> {
                    output.append(content);
                    System.out.print(content);
                    System.out.flush();
                },
                showThinking ? (thinkingToken -> {
                    System.err.print(thinkingToken);
                    System.err.flush();
                }) : null
            );

            String result = openAiProvider.chatWithToolLoop(model, messages, tools, executor, handlers, 5);
            System.out.println();
            return result;
        }

        if (rawOutput) {
            // Return raw response
            return llmProvider.getRawResponse(model, messages);
        } else {
            // Stream response
            StringBuilder output = new StringBuilder();
            boolean suppressOutput = shortMode && llmProvider.supportsStreaming();

            // Warn if thinking mode is requested but provider doesn't support streaming
            if (showThinking && !llmProvider.supportsStreaming()) {
                System.err.println("Note: --think mode not supported by this provider (no streaming support)");
            }

            LlmProvider.StreamHandlers handlers = createStreamHandlers(
                output, showThinking, suppressOutput);

            llmProvider.chat(model, messages, handlers);
            if (!suppressOutput) {
                System.out.println(); // Final newline
            }
            return output.toString();
        }
    }

    /**
     * Creates stream handlers that properly handle &lt;think&gt;...&lt;/think&gt; blocks,
     * including tokens split across chunk boundaries (e.g. "&lt;thi" + "nk&gt;").
     *
     * @param output The buffer to accumulate the final response text
     * @param showThinking Whether to emit thinking tokens to stderr
     * @param suppressOutput If true, don't print to stdout (used for short mode)
     */
    private LlmProvider.StreamHandlers createStreamHandlers(
            StringBuilder output, boolean showThinking, boolean suppressOutput) {

        // Buffer for handling partial tags split across chunks
        StringBuilder tagBuffer = new StringBuilder();
        AtomicBoolean inThinkBlock = new AtomicBoolean(false);

        return new LlmProvider.StreamHandlers(
            content -> {
                // Append to tag buffer and process
                tagBuffer.append(content);
                processTagBuffer(tagBuffer, inThinkBlock, output, showThinking, suppressOutput);
            },
            showThinking && llmProvider.supportsStreaming() ? (thinkingToken -> {
                System.err.print(thinkingToken);
                System.err.flush();
            }) : null
        );
    }

    /**
     * Processes the tag buffer, extracting complete tags and emitting content appropriately.
     * Handles the case where &lt;think&gt; or &lt;/think&gt; tags are split across chunks.
     */
    private void processTagBuffer(StringBuilder buffer, AtomicBoolean inThinkBlock,
                                   StringBuilder output, boolean showThinking, boolean suppressOutput) {
        while (buffer.length() > 0) {
            String text = buffer.toString();

            if (inThinkBlock.get()) {
                // Inside a think block — look for </think>
                int closeIdx = text.indexOf("</think>");
                if (closeIdx >= 0) {
                    // Found close tag
                    String thinking = text.substring(0, closeIdx);
                    if (showThinking && !thinking.isEmpty()) {
                        System.err.print(thinking);
                        System.err.flush();
                    }
                    inThinkBlock.set(false);
                    buffer.delete(0, closeIdx + "</think>".length());
                    continue;
                }
                // Check if buffer might contain a partial </think> at the end
                if (text.length() >= 8 || !couldBePartialTag(text, "</think>")) {
                    // Safe to emit what we have (minus potential partial tag at end)
                    int safeEnd = findSafeEnd(text, "</think>");
                    String safe = text.substring(0, safeEnd);
                    if (showThinking && !safe.isEmpty()) {
                        System.err.print(safe);
                        System.err.flush();
                    }
                    buffer.delete(0, safeEnd);
                }
                return; // Wait for more data
            } else {
                // Outside think block — look for <think>
                int openIdx = text.indexOf("<think>");
                if (openIdx >= 0) {
                    // Emit content before the tag
                    String before = text.substring(0, openIdx);
                    if (!before.isEmpty()) {
                        emitContent(before, output, suppressOutput);
                    }
                    inThinkBlock.set(true);
                    buffer.delete(0, openIdx + "<think>".length());
                    continue;
                }
                // Check if buffer ends with a partial <think> tag
                if (text.length() >= 7 || !couldBePartialTag(text, "<think>")) {
                    int safeEnd = findSafeEnd(text, "<think>");
                    String safe = text.substring(0, safeEnd);
                    if (!safe.isEmpty()) {
                        emitContent(safe, output, suppressOutput);
                    }
                    buffer.delete(0, safeEnd);
                }
                return; // Wait for more data
            }
        }
    }

    private void emitContent(String content, StringBuilder output, boolean suppressOutput) {
        output.append(content);
        if (!suppressOutput) {
            System.out.print(content);
            System.out.flush();
        }
    }

    /**
     * Checks if the end of text could be the start of a partial tag.
     */
    private boolean couldBePartialTag(String text, String tag) {
        for (int len = 1; len < tag.length() && len <= text.length(); len++) {
            if (text.endsWith(tag.substring(0, len))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the index up to which it's safe to emit, avoiding splitting a potential partial tag.
     */
    private int findSafeEnd(String text, String tag) {
        for (int len = tag.length() - 1; len >= 1; len--) {
            if (text.endsWith(tag.substring(0, len))) {
                return text.length() - len;
            }
        }
        return text.length();
    }

    /**
     * Creates a short summary of the analysis by running it through the LLM again.
     */
    private String createShortSummary(String model, String fullAnalysis, boolean isSystemMode, boolean showThinking)
            throws IOException, LlmProvider.LlmException {

        System.err.println("\n--- Creating short summary ---\n");

        String summaryPrompt = getSummaryPrompt(fullAnalysis, isSystemMode);

        List<LlmProvider.Message> messages = new ArrayList<>();
        messages.add(new LlmProvider.Message("system", SYSTEM_PROMPT));
        messages.add(new LlmProvider.Message("user", summaryPrompt));

        StringBuilder summary = new StringBuilder();

        LlmProvider.StreamHandlers handlers = new LlmProvider.StreamHandlers(
            content -> {
                summary.append(content);
                System.out.print(content);
                System.out.flush();
            },
            showThinking && llmProvider.supportsStreaming() ? (thinkingToken -> {
                System.err.print(thinkingToken);
                System.err.flush();
            }) : null
        );

        llmProvider.chat(model, messages, handlers);
        System.out.println(); // Final newline

        return summary.toString();
    }

    private static @NotNull String getSummaryPrompt(String fullAnalysis, boolean isSystemMode) {
        String summaryPrompt;
        if (isSystemMode) {
            summaryPrompt = "Analyze the following system-wide analysis and provide a succinct summary of the current state of the system.\n" +
                            "Focus on the most important findings and issues. Be specific and data-driven.\n" +
                            "Keep it to 3-5 key points maximum. Don't give any advice, just state the state. Don't repeat yourself.\n\n" +
                            "Full Analysis:\n---\n" + fullAnalysis + "\n---";
        } else {
            summaryPrompt = "Analyze the following application analysis and provide a succinct summary of the current state of the application.\n" +
                            "Focus on the most important findings and issues. Be specific and data-driven.\n" +
                            "Keep it to 3-5 key points maximum. Don't give any advice, just state the state. Don't repeat yourself.\n\n" +
                            "Full Analysis:\n---\n" + fullAnalysis + "\n---";
        }
        return summaryPrompt;
    }
}