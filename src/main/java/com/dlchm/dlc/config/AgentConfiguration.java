package com.dlchm.dlc.config;

import com.dlchm.dlc.sandbox.BashCommandSandbox;
import com.dlchm.dlc.sandbox.SandboxPathResolver;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
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
    public BashCommandSandbox bashCommandSandbox() {
        return new BashCommandSandbox();
    }

    @Bean
    public ToolOutputTruncator toolOutputTruncator(DlcProperties props) {
        return new ToolOutputTruncator(props.getMaxToolOutputChars());
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
            BrowserTool browserTool) {
        List<Object> tools = new ArrayList<>();
        tools.add(new ReadFileTool(pathResolver, truncator));
        tools.add(new WriteFileTool(pathResolver, props));
        tools.add(new EditFileTool(pathResolver, props));
        tools.add(new GlobSearchTool(pathResolver, truncator));
        tools.add(new GrepSearchTool(pathResolver, truncator));
        tools.add(new BashExecuteTool(pathResolver, bashSandbox, truncator, props));
        tools.add(memoryTool);
        tools.add(browserTool);
        if (skillsTool.hasSkills()) {
            tools.add(skillsTool);
        }
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools.toArray())
                .build();
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
