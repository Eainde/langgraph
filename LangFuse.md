# Langfuse + LangChain4j — Spring Boot Micrometer OTel Native Integration

## Core Idea

**Zero manual OTel setup.** Spring Boot auto-configures the entire tracing pipeline:

```
Spring Boot Actuator
  → Micrometer Observation API
    → micrometer-tracing-bridge-otel
      → OtlpHttpSpanExporter
        → Langfuse OTLP Endpoint
```

Every HTTP request, `@Observed` method, and custom span automatically shares the **same trace/span IDs** propagated by Spring. Your LangChain4j `ChatModelListener` just participates in the existing trace context — no separate `TracerProvider` needed.

```
                         Spring Boot Auto-Configuration
                    ┌──────────────────────────────────────┐
  HTTP Request      │  ObservationRegistry (Micrometer)    │
  ──────────────────►  ┌────────────────────────────┐      │
  traceId=abc123    │  │ micrometer-tracing-bridge-  │      │
                    │  │ otel (bridges to OTel SDK)  │      │
                    │  └────────────┬───────────────┘      │
                    │               │                       │
                    │  ┌────────────▼───────────────┐      │
                    │  │ OtlpHttpSpanExporter        │      │
                    │  │ → Langfuse /api/public/otel │      │
                    │  └────────────────────────────┘      │
                    └──────────────────────────────────────┘
                                    │
                    ┌───────────────▼──────────────────────┐
                    │        Langfuse Dashboard             │
                    │  Same traceId=abc123 for:             │
                    │   • HTTP endpoint span                │
                    │   • Agent workflow span (@Observed)   │
                    │   • LLM generation span (Listener)   │
                    │   • Tool call span                    │
                    └──────────────────────────────────────┘
```

---

## Step 1: Maven Dependencies

```xml
<properties>
    <langchain4j.version>1.0.0-beta1</langchain4j.version>
    <spring-boot.version>3.4.1</spring-boot.version>
</properties>

<dependencies>
    <!-- ═══ Spring Boot Core ═══ -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- ═══ Spring Boot Actuator (enables Micrometer observation + tracing) ═══ -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- ═══ Micrometer → OTel Bridge (auto-configured by Spring Boot) ═══ -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>

    <!-- ═══ OTLP Exporter (Spring Boot auto-configures this as a bean) ═══ -->
    <dependency>
        <groupId>io.opentelemetry</groupId>
        <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>

    <!-- ═══ AOP for @Observed annotation ═══ -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-observation</artifactId>
    </dependency>

    <!-- ═══ LangChain4j ═══ -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>

    <!-- ═══ Langfuse Official Java Client (prompts, scores, ingestion) ═══ -->
    <dependency>
        <groupId>com.langfuse</groupId>
        <artifactId>langfuse-java</artifactId>
        <version>0.1.0</version>
    </dependency>
</dependencies>
```

> **Spring Boot 4.x users**: Replace `spring-boot-starter-actuator` + `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` with the single `spring-boot-starter-opentelemetry`. Same result, one dependency.

---

## Step 2: application.yml — Just Properties, No Java Config

```yaml
spring:
  application:
    name: nexus-ai-agent-service

# ═══ Langfuse OTLP endpoint — this is ALL you need for tracing ═══
management:
  otlp:
    tracing:
      endpoint: ${LANGFUSE_OTLP_ENDPOINT:https://cloud.langfuse.com/api/public/otel/v1/traces}
      headers:
        Authorization: "Basic ${LANGFUSE_AUTH_TOKEN}"
  tracing:
    sampling:
      probability: 1.0   # 100% in dev; tune down in prod (e.g., 0.1)
  observations:
    annotations:
      enabled: true       # Enables @Observed on any bean method

# ═══ Langfuse Java Client config ═══
langfuse:
  url: ${LANGFUSE_URL:https://cloud.langfuse.com}
  public-key: ${LANGFUSE_PUBLIC_KEY}
  secret-key: ${LANGFUSE_SECRET_KEY}

# ═══ LangChain4j model config ═══
langchain4j:
  open-ai:
    chat-model:
      api-key: ${OPENAI_API_KEY}
      model-name: gpt-4o
      temperature: 0.3
```

**That's it for tracing setup.** Spring Boot auto-configures:
- `OtlpHttpSpanExporter` → pointed at Langfuse
- `SdkTracerProvider` with the exporter
- `io.micrometer.tracing.Tracer` bean wired to OTel bridge
- Context propagation (W3C Trace Context) across HTTP, `@Async`, etc.

### Generating the auth token

```bash
# Set as environment variable
export LANGFUSE_AUTH_TOKEN=$(echo -n "${LANGFUSE_PUBLIC_KEY}:${LANGFUSE_SECRET_KEY}" | base64)
```

---

## Step 3: Langfuse Client Bean

```java
package com.db.nexusai.config;

import com.langfuse.client.LangfuseClient;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseConfig {

    private String url = "https://cloud.langfuse.com";
    private String publicKey;
    private String secretKey;

    @Bean
    public LangfuseClient langfuseClient() {
        return LangfuseClient.builder()
            .url(url)
            .credentials(publicKey, secretKey)
            .build();
    }
}
```

---

## Step 4: ChatModelListener — Using Spring's Micrometer Tracer

The key difference: we inject `io.micrometer.tracing.Tracer` (not `io.opentelemetry.api.trace.Tracer`). This tracer is **already wired into Spring's trace context**, so the LLM spans automatically become children of the current HTTP request span.

```java
package com.db.nexusai.langfuse;

import dev.langchain4j.model.chat.listener.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.stream.Collectors;

/**
 * LangChain4j ChatModelListener that creates child spans using Spring's
 * Micrometer Tracer. Spans automatically:
 *   - Inherit the current traceId from the HTTP request / parent span
 *   - Export to Langfuse via the auto-configured OTLP exporter
 *   - Show up as "generations" in Langfuse with GenAI semantic attributes
 */
@Component
@Slf4j
public class LangfuseChatModelListener implements ChatModelListener {

    private final Tracer tracer;

    private static final String KEY_SPAN  = "lf.span";
    private static final String KEY_START = "lf.start";

    public LangfuseChatModelListener(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        Instant start = Instant.now();
        String model = resolveModel(ctx);

        // Creates a child span under the current active span (e.g., HTTP request)
        // Spring's Tracer handles parent-child relationship automatically
        Span span = tracer.nextSpan()
            .name("chat " + model)
            .tag("gen_ai.operation.name", "chat")
            .tag("gen_ai.system", resolveProvider(ctx))
            .tag("gen_ai.request.model", model)
            .start();

        // Add prompt content as a tag (Langfuse parses gen_ai.* attributes)
        String promptSummary = ctx.chatRequest().messages().stream()
            .map(m -> m.type().name() + ": " +
                (m.text() != null ? m.text().substring(0,
                    Math.min(m.text().length(), 500)) : ""))
            .collect(Collectors.joining(" | "));
        span.tag("gen_ai.prompt", promptSummary);

        // Store in attributes map for onResponse/onError
        ctx.attributes().put(KEY_SPAN, span);
        ctx.attributes().put(KEY_START, start);
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        Span span = (Span) ctx.attributes().get(KEY_SPAN);
        Instant start = (Instant) ctx.attributes().get(KEY_START);
        if (span == null) return;

        try {
            ChatResponse response = ctx.chatResponse();
            TokenUsage usage = response.tokenUsage();

            // GenAI semantic attributes — Langfuse maps these to its data model
            if (usage != null) {
                span.tag("gen_ai.usage.input_tokens",
                    String.valueOf(usage.inputTokenCount()));
                span.tag("gen_ai.usage.output_tokens",
                    String.valueOf(usage.outputTokenCount()));
                span.tag("gen_ai.usage.total_tokens",
                    String.valueOf(usage.totalTokenCount()));
            }

            String completion = response.aiMessage().text();
            if (completion != null) {
                span.tag("gen_ai.completion",
                    completion.substring(0, Math.min(completion.length(), 500)));
            }

            span.tag("gen_ai.response.finish_reasons",
                response.finishReason() != null
                    ? response.finishReason().name() : "UNKNOWN");

            log.debug("LLM call traced | model={} | tokens={} | {}ms",
                resolveModel(ctx), usage != null ? usage.totalTokenCount() : "?",
                java.time.Duration.between(start, Instant.now()).toMillis());

        } finally {
            span.end(); // Completes the span — exported to Langfuse via OTLP
        }
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        Span span = (Span) ctx.attributes().get(KEY_SPAN);
        if (span != null) {
            span.tag("error", ctx.error().getMessage());
            span.error(ctx.error());
            span.end();
        }
        log.error("LLM call failed: {}", ctx.error().getMessage());
    }

    // ── Helpers ──

    private String resolveModel(ChatModelRequestContext ctx) {
        var params = ctx.chatRequest().parameters();
        return params != null && params.modelName() != null
            ? params.modelName() : "unknown";
    }

    private String resolveModel(ChatModelResponseContext ctx) {
        return ctx.chatResponse().metadata() != null
            ? ctx.chatResponse().metadata()
                .getOrDefault("model", "unknown").toString()
            : "unknown";
    }

    private String resolveProvider(ChatModelRequestContext ctx) {
        return ctx.chatModel().provider() != null
            ? ctx.chatModel().provider().toString() : "unknown";
    }
}
```

---

## Step 5: Wire Listener to ChatModel

```java
package com.db.nexusai.config;

import com.db.nexusai.langfuse.LangfuseChatModelListener;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AiModelConfig {

    @Bean
    public ChatLanguageModel chatLanguageModel(
            @Value("${langchain4j.open-ai.chat-model.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String model,
            LangfuseChatModelListener langfuseListener) {

        return OpenAiChatModel.builder()
            .apiKey(apiKey)
            .modelName(model)
            .temperature(0.3)
            .listeners(List.of(langfuseListener))
            .build();
    }
}
```

---

## Step 6: Prompt Management (langfuse-java Client)

```java
package com.db.nexusai.langfuse;

import com.langfuse.client.LangfuseClient;
import com.langfuse.client.resources.prompts.types.Prompt;
import com.langfuse.client.resources.prompts.requests.GetPromptRequest;
import dev.langchain4j.data.message.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class LangfusePromptService {

    private final LangfuseClient langfuseClient;
    private final Map<String, CachedPrompt> cache = new ConcurrentHashMap<>();
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    public LangfusePromptService(LangfuseClient langfuseClient) {
        this.langfuseClient = langfuseClient;
    }

    public String getText(String name, Map<String, String> vars) {
        return compile(fetchPrompt(name).getPrompt().orElse(""), vars);
    }

    public List<ChatMessage> getChatMessages(String name, Map<String, String> vars) {
        Prompt prompt = fetchPrompt(name);
        if (prompt.getChatPrompt().isPresent()) {
            List<ChatMessage> msgs = new ArrayList<>();
            for (var msg : prompt.getChatPrompt().get()) {
                String content = compile(msg.getContent(), vars);
                switch (msg.getRole()) {
                    case "system"    -> msgs.add(SystemMessage.from(content));
                    case "user"      -> msgs.add(UserMessage.from(content));
                    case "assistant" -> msgs.add(AiMessage.from(content));
                }
            }
            return msgs;
        }
        return List.of(UserMessage.from(compile(prompt.getPrompt().orElse(""), vars)));
    }

    private Prompt fetchPrompt(String name) {
        CachedPrompt cached = cache.get(name);
        if (cached != null && cached.isValid()) return cached.prompt;

        Prompt prompt = langfuseClient.prompts().get(name,
            GetPromptRequest.builder().label("production").build());
        cache.put(name, new CachedPrompt(prompt, Instant.now()));
        log.info("Loaded Langfuse prompt '{}' v{}", name, prompt.getVersion());
        return prompt;
    }

    private String compile(String tpl, Map<String, String> vars) {
        String r = tpl;
        for (var e : vars.entrySet())
            r = r.replace("{{" + e.getKey() + "}}", e.getValue());
        return r;
    }

    private record CachedPrompt(Prompt prompt, Instant fetchedAt) {
        boolean isValid() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) < 0;
        }
    }
}
```

---

## Step 7: Score Service (langfuse-java Client)

```java
package com.db.nexusai.langfuse;

import com.langfuse.client.LangfuseClient;
import com.langfuse.client.resources.score.requests.CreateScoreRequest;
import com.langfuse.client.resources.commons.types.CreateScoreValue;
import com.langfuse.client.resources.commons.types.ScoreDataType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class LangfuseScoreService {

    private final LangfuseClient langfuseClient;

    public LangfuseScoreService(LangfuseClient langfuseClient) {
        this.langfuseClient = langfuseClient;
    }

    @Async
    public void scoreNumeric(String traceId, String name, double value, String comment) {
        try {
            langfuseClient.score().create(CreateScoreRequest.builder()
                .traceId(traceId).name(name)
                .value(CreateScoreValue.of(value))
                .dataType(ScoreDataType.NUMERIC)
                .comment(comment).build());
        } catch (Exception e) {
            log.warn("Score failed for trace {}: {}", traceId, e.getMessage());
        }
    }

    @Async
    public void scoreBoolean(String traceId, String name, boolean value) {
        try {
            langfuseClient.score().create(CreateScoreRequest.builder()
                .traceId(traceId).name(name)
                .value(CreateScoreValue.of(value ? 1.0 : 0.0))
                .dataType(ScoreDataType.BOOLEAN).build());
        } catch (Exception e) {
            log.warn("Score failed for trace {}: {}", traceId, e.getMessage());
        }
    }
}
```

---

## Step 8: Agent with @Observed — No Manual Span Code

Using Micrometer's `@Observed` annotation. Spring auto-creates a span named after the method, and it becomes a child of the current trace. All `chatModel.chat()` calls inside get their own child spans via the `ChatModelListener`.

```java
package com.db.nexusai.agent;

import com.db.nexusai.langfuse.LangfusePromptService;
import com.db.nexusai.langfuse.LangfuseScoreService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.observation.annotation.Observed;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class CsmExtractionAgent {

    private final ChatLanguageModel chatModel;
    private final LangfusePromptService promptService;
    private final LangfuseScoreService scoreService;
    private final Tracer tracer;

    public CsmExtractionAgent(ChatLanguageModel chatModel,
                               LangfusePromptService promptService,
                               LangfuseScoreService scoreService,
                               Tracer tracer) {
        this.chatModel = chatModel;
        this.promptService = promptService;
        this.scoreService = scoreService;
        this.tracer = tracer;
    }

    /**
     * @Observed creates an automatic span named "csm-extraction-workflow"
     * It becomes a child of the current HTTP request trace.
     * All chatModel.chat() calls inside produce their own child spans
     * via the LangfuseChatModelListener.
     *
     * Result in Langfuse:
     *   HTTP POST /api/extract (root span)
     *     └─ csm-extraction-workflow (this method)
     *         ├─ chat gpt-4o (extraction LLM call)
     *         └─ chat gpt-4o (validation LLM call)
     */
    @Observed(name = "csm-extraction-workflow",
              contextualName = "csm-extraction-workflow")
    public ExtractionResult extract(String documentContent, String sessionId) {

        // Get the current trace ID — Spring manages this
        String traceId = tracer.currentSpan() != null
            ? tracer.currentSpan().context().traceId() : "unknown";

        // Step 1: Extract using prompt from Langfuse
        List<ChatMessage> extractionMsgs = promptService.getChatMessages(
            "csm-extraction-v2",
            Map.of("document", documentContent));

        ChatResponse extractionResponse = chatModel.chat(
            ChatRequest.builder().messages(extractionMsgs).build());
        String extracted = extractionResponse.aiMessage().text();

        // Step 2: Validate using critic pattern
        List<ChatMessage> validationMsgs = promptService.getChatMessages(
            "csm-validator",
            Map.of("extracted_data", extracted,
                   "original_document", documentContent));

        ChatResponse validationResponse = chatModel.chat(
            ChatRequest.builder().messages(validationMsgs).build());
        String validation = validationResponse.aiMessage().text();

        // Step 3: Score via Langfuse Java Client
        scoreService.scoreNumeric(traceId, "completeness", 0.95,
            "All CSM fields extracted");
        scoreService.scoreBoolean(traceId, "validation_passed", true);

        return new ExtractionResult(extracted, validation, traceId);
    }

    public record ExtractionResult(String extracted, String validation, String traceId) {}
}
```

### For more granular sub-steps, inject the Micrometer Tracer:

```java
@Observed(name = "csm-comparison-workflow")
public ComparisonResult compare(String docA, String docB) {

    // Manual child span when @Observed isn't granular enough
    Span extractA = tracer.nextSpan().name("extract-doc-a").start();
    try (Tracer.SpanInScope ws = tracer.withSpan(extractA)) {
        // chatModel.chat() calls here get their own child spans
        // ...
    } finally {
        extractA.end();
    }

    Span extractB = tracer.nextSpan().name("extract-doc-b").start();
    try (Tracer.SpanInScope ws = tracer.withSpan(extractB)) {
        // ...
    } finally {
        extractB.end();
    }
}
```

---

## Step 9: REST Controller — Trace Flows End-to-End

```java
package com.db.nexusai.controller;

import com.db.nexusai.agent.CsmExtractionAgent;
import com.db.nexusai.agent.CsmExtractionAgent.ExtractionResult;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AgentController {

    private final CsmExtractionAgent extractionAgent;

    public AgentController(CsmExtractionAgent extractionAgent) {
        this.extractionAgent = extractionAgent;
    }

    /**
     * The trace in Langfuse will show:
     *
     *   HTTP POST /api/extract           ← auto-instrumented by Spring
     *     └─ csm-extraction-workflow     ← @Observed
     *         ├─ chat gpt-4o             ← ChatModelListener (extraction)
     *         └─ chat gpt-4o             ← ChatModelListener (validation)
     */
    @PostMapping("/extract")
    public ExtractionResult extract(@RequestBody ExtractionRequest request) {
        return extractionAgent.extract(request.document(), request.sessionId());
    }

    public record ExtractionRequest(String document, String sessionId) {}
}
```

---

## What Changed vs. Previous Versions

| Before (Manual OTel) | Now (Spring Micrometer Native) |
|---|---|
| Manual `SdkTracerProvider` + `OtlpHttpSpanExporter` beans | **Deleted.** Spring auto-configures via `management.otlp.tracing.endpoint` |
| `io.opentelemetry.api.trace.Tracer` injected everywhere | `io.micrometer.tracing.Tracer` — Spring's abstraction, auto-wired |
| Manual `Span.startSpan()` + `Scope` management | `@Observed` annotation for most cases, `tracer.nextSpan()` for granular control |
| Separate trace IDs for OTel spans vs HTTP spans | **Same traceId everywhere** — HTTP, agent, LLM calls all linked |
| Custom `@LangfuseTraced` AOP aspect | **Replaced by `@Observed`** — built into Micrometer, no custom aspect needed |
| `OtelLangfuseConfig.java` (50+ lines) | **Deleted entirely.** Just `application.yml` properties |

## What Uses What

| Concern | Tool | How |
|---------|------|-----|
| **Tracing (spans)** | Spring Micrometer → OTel → Langfuse OTLP | `management.otlp.tracing.endpoint` + auto-config |
| **LLM call tracing** | LangChain4j `ChatModelListener` | Creates child span via `tracer.nextSpan()` |
| **Workflow tracing** | `@Observed` annotation | Spring creates spans automatically |
| **Prompt management** | `langfuse-java` client | `client.prompts().get()` |
| **Scoring** | `langfuse-java` client | `client.score().create()` |
| **Trace ID access** | `tracer.currentSpan().context().traceId()` | For linking scores to the current trace |

## File Count: 7 Files Total

```
config/
  LangfuseConfig.java           ← LangfuseClient bean + properties
  AiModelConfig.java            ← ChatModel + listener wiring
langfuse/
  LangfuseChatModelListener.java ← Auto-traces LLM calls
  LangfusePromptService.java     ← Prompt management (cached)
  LangfuseScoreService.java      ← Evaluation scores
agent/
  CsmExtractionAgent.java        ← Your agents, @Observed
controller/
  AgentController.java           ← REST endpoints
application.yml                  ← All config, zero Java OTel boilerplate
```