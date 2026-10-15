package com.dlchm.dlc.config;

import com.dlchm.dlc.sandbox.BashCommandSandbox;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
import com.dlchm.dlc.agent.ObservingToolCallbackProvider;
import com.dlchm.dlc.agent.ToolCallTimingListener;
import com.dlchm.dlc.tools.BashExecuteTool;
import com.dlchm.dlc.tools.EditFileTool;
import com.dlchm.dlc.tools.GlobSearchTool;
import com.dlchm.dlc.tools.GrepSearchTool;
import com.dlchm.dlc.tools.ReadFileTool;
import com.dlchm.dlc.tools.MemoryTool;
import com.dlchm.dlc.tools.SkillsTool;
import com.dlchm.dlc.tools.ToolOutputTruncator;
import com.dlchm.dlc.tools.BrowserTool;
import com.dlchm.dlc.tools.WriteFileTool;
import com.dlchm.dlc.tools.SubagentTool;
import com.dlchm.dlc.tools.DlcSubagentDefinition;
import com.dlchm.dlc.tools.DlcSubagentResolver;
import com.dlchm.dlc.agent.SubagentManager;
import com.dlchm.dlc.agent.ContextPropagatingTaskRepository;
import com.dlchm.dlc.session.MarkdownSessionStore;
import com.dlchm.dlc.agent.ApprovalManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springaicommunity.agent.common.task.subagent.SubagentExecutor;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.common.task.subagent.TaskCall;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.util.StreamUtils;

/**
 * DLC Agent Bean 组装。
 */
@Configuration
@EnableConfigurationProperties(DlcProperties.class)
public class AgentConfiguration {

    @Bean
    public SandboxPathResolver sandboxPathResolver(DlcProperties props) {
        return new SandboxPathResolver(props.getWorkspace(), props.getBlockedPaths());
    }

    @Bean
    public MarkdownSessionStore markdownSessionStore(SandboxPathResolver pathResolver) {
        return new MarkdownSessionStore(pathResolver);
    }

    @Bean
    public BashCommandSandbox bashCommandSandbox() {
        return new BashCommandSandbox();
    }

    @Bean
    public ToolOutputTruncator toolOutputTruncator(DlcProperties props) {
        return new ToolOutputTruncator(props.getMaxToolOutputChars());
    }

    @Bean(destroyMethod = "shutdown")
    public ContextPropagatingTaskRepository dlcTaskRepository() {
        return new ContextPropagatingTaskRepository();
    }

    @Bean("dlcTaskTool")
    public ToolCallback dlcTaskTool(SubagentManager subagentManager, TaskRepository taskRepository) {
        DlcSubagentResolver resolver = new DlcSubagentResolver();
        List<SubagentReference> references = specialistReferences();
        SubagentExecutor executor = new SubagentExecutor() {
            @Override
            public String getKind() {
                return DlcSubagentResolver.KIND;
            }

            @Override
            public String execute(TaskCall call,
                                  org.springaicommunity.agent.common.task.subagent.SubagentDefinition definition) {
                if (call.model() != null && !call.model().isBlank()) {
                    return "Error: this DLC instance uses one configured model; omit the optional model override.";
                }
                if (call.resume() != null && !call.resume().isBlank()) {
                    return "Error: resuming a prior Task ID is not supported; provide a self-contained new task.";
                }
                DlcSubagentDefinition specialist = (DlcSubagentDefinition) definition;
                String prompt = "任务描述：" + call.description() + "\n\n任务要求：\n" + call.prompt();
                return subagentManager.delegate(prompt, null, specialist.instructions());
            }
        };
        String description = """
                Delegate one self-contained task to a named specialist.
                Available specialists:\n%s
                The child runs in an isolated DLC session and does not see the parent conversation. Include all facts, paths, constraints, and expected output in the task. A child cannot delegate further. Background execution is available through TaskOutput. The configured DLC model is used for every child.
                """;
        return TaskTool.builder()
                .subagentTypes(new SubagentType(resolver, executor))
                .subagentReferences(references)
                .taskRepository(taskRepository)
                .taskDescriptionTemplate(description)
                .build();
    }

    @Bean("dlcTaskOutputTool")
    public ToolCallback dlcTaskOutputTool(TaskRepository taskRepository) {
        return TaskOutputTool.builder()
                .taskRepository(taskRepository)
                .build();
    }

    @Bean
    public SkillsTool skillsTool() {
        return new SkillsTool();
    }

    @Bean
    public MemoryTool memoryTool(SandboxPathResolver pathResolver) {
        return new MemoryTool(pathResolver.getWorkspaceRoot());
    }

    @Bean
    public BrowserTool browserTool(ToolOutputTruncator truncator) {
        extractBuiltinScripts();
        return new BrowserTool(truncator);
    }

    @Bean
    public ToolCallbackProvider codingTools(
            SandboxPathResolver pathResolver,
            BashCommandSandbox bashSandbox,
            ToolOutputTruncator truncator,
            DlcProperties props,
            SkillsTool skillsTool,
            MemoryTool memoryTool,
            BrowserTool browserTool,
            ApprovalManager approvalManager,
            SubagentManager subagentManager,
            @Qualifier("dlcTaskTool") ToolCallback taskTool,
            @Qualifier("dlcTaskOutputTool") ToolCallback taskOutputTool) {
        List<Object> tools = new ArrayList<>();
        tools.add(new ReadFileTool(pathResolver, truncator));
        tools.add(new WriteFileTool(pathResolver, props));
        tools.add(new EditFileTool(pathResolver, props));
        tools.add(new GlobSearchTool(pathResolver, truncator));
        tools.add(new GrepSearchTool(pathResolver, truncator));
        tools.add(new BashExecuteTool(pathResolver, bashSandbox, truncator, props, approvalManager));
        tools.add(memoryTool);
        tools.add(browserTool);
        // Always registered: the callback list is built once at startup, so
        // gating registration on the switch would make `enabled` a boot-time
        // only setting. SubagentManager refuses the call instead, which is
        // what makes the toggle work from `/config` without a restart.
        tools.add(new SubagentTool(subagentManager));
        if (skillsTool.hasSkills()) {
            tools.add(skillsTool);
        }
        // Wrapped rather than built in: the callbacks are produced by the provider, so
        // the timing listener has to decorate them afterwards. Doing it inside each
        // tool would mean touching every one and keeping that code in sync.
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
        List<ToolCallback> callbacks = new ArrayList<>(List.of(provider.getToolCallbacks()));
        callbacks.add(taskTool);
        callbacks.add(taskOutputTool);
        ToolCallbackProvider combined = () -> callbacks.toArray(ToolCallback[]::new);
        return new ObservingToolCallbackProvider(combined, new ToolCallTimingListener());
    }

    private List<SubagentReference> specialistReferences() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:agents/*.md");
            List<SubagentReference> references = new ArrayList<>();
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null && filename.endsWith(".md")) {
                    references.add(new SubagentReference("classpath:/agents/" + filename,
                            DlcSubagentResolver.KIND));
                }
            }
            if (references.isEmpty()) {
                throw new IllegalStateException("No DLC specialist definitions found under classpath:agents/");
            }
            return references;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot enumerate DLC specialist definitions", e);
        }
    }

    /**
     * Extract built-in Python scripts from JAR to ~/.dlc/scripts/ for BrowserTool to invoke.
     */
    private void extractBuiltinScripts() {
        String[] scripts = {"browser_cdp.py"};
        Path scriptsDir = Path.of(System.getProperty("user.home"), ".dlc", "scripts");
        try {
            Files.createDirectories(scriptsDir);
            for (String script : scripts) {
                try (InputStream is = new ClassPathResource("scripts/" + script).getInputStream()) {
                    Path target = scriptsDir.resolve(script);
                    try (OutputStream os = Files.newOutputStream(target)) {
                        is.transferTo(os);
                    }
                } catch (IOException e) {
                    // Script not found in classpath, skip
                }
            }
        } catch (IOException e) {
            // Cannot create scripts dir, browser tools will report error
        }
    }

    @Bean
    public String systemPromptTemplate() {
        try (InputStream is = new ClassPathResource("prompts/system-prompt.txt").getInputStream()) {
            return StreamUtils.copyToString(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load system prompt", e);
        }
    }
}
