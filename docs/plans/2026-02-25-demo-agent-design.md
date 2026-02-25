# Demo Agent Design

Date: 2026-02-25

## Overview

Add a demo mode to the CLI agent that demonstrates three conversation scenarios:
1. Short dialog (3 exchanges, hardcoded messages)
2. Long dialog (15+ exchanges, self-continuing via model-generated questions)
3. Context overflow dialog (exceeds model's token limit, API error captured)

## Launch

`java -jar agent.jar --demo` — runs all three scenarios sequentially and exits.
No arguments — normal interactive mode (existing behavior).

## Architecture

### `Main.kt`
Parse first argument: `"--demo"` → `DemoAgent(apiKey).run()`, otherwise → `CliAgent(apiKey).run()`.

### `DemoAgent.kt`
New class. Uses existing `ClaudeClient` and `ConversationSession`.
- `run()` — calls the three scenario methods in order
- `runShortDemo()` — 3 hardcoded exchanges
- `runLongDemo()` — 15 exchanges, self-continuing
- `runOverflowDemo()` — sends ~220k token message, catches API error

## Scenarios

### Scenario 1 — Short Dialog
- 3 hardcoded user messages on a fixed topic (e.g. French geography)
- Prints each user message, assistant reply, and token/cost stats after each turn

### Scenario 2 — Long Dialog
- Starter message hardcoded: "Давай обсудим историю Римской империи. Начни с основания."
- After each reply, the last sentence of the response is extracted and sent as next user message
- Runs for 15 steps autonomously
- Shows cumulative token and cost accumulation

### Scenario 3 — Overflow
- Sends a message of ~220,000 tokens (repeated word string) with maxTokens=200_000
- ClaudeClient.ask() throws on !response.isSuccessful
- DemoAgent catches the exception and prints a human-readable error

## Token/Cost Display
Reuses existing session.lastUsage, session.sessionUsage, and ClaudeClient.costUsd() / formatCost().

## Files Changed
- `Main.kt` — add args parsing
- `DemoAgent.kt` — new file
