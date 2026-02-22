package tech.lenooby09.openklaw.skills

/**
 * Default skills bundled with Open-Klaw.
 * These are auto-approved and always available.
 */
object BundledSkills {

	val WEB_RESEARCH = """
# Web Research

**id:** web-research
**version:** 1.0.0
**author:** open-klaw
**tags:** research, web, search, information

## Description
Skill for conducting structured web research using available browser and search tools.

## Instructions
When the user asks you to research a topic:
1. Break the question into specific sub-queries
2. Use the browser tool to navigate to relevant sources
3. Extract key facts, dates, and figures
4. Cross-reference information from multiple sources when possible
5. Summarize findings with source attribution
6. Highlight any conflicting information or uncertainties

Always prefer primary sources over secondary ones. When citing statistics, include the date and source. If information might be outdated, say so explicitly.

## Examples
User: "Research the latest developments in quantum computing"
Agent approach:
- Search for recent quantum computing breakthroughs
- Check major research institutions (IBM, Google, academic papers)
- Summarize key milestones, timelines, and implications
- Note which claims are verified vs. speculative

## Context
Common research domains: technology, science, business, health, history, current events.
Quality indicators: peer-reviewed sources, official documentation, reputable news outlets.
Red flags: unverified claims, outdated information, single-source conclusions.
""".trimIndent()

	val FILE_MANAGEMENT = """
# File Management

**id:** file-management
**version:** 1.0.0
**author:** open-klaw
**tags:** files, organization, filesystem, productivity

## Description
Skill for intelligent file organization, cleanup, and management tasks.

## Instructions
When helping with file management:
1. Always confirm destructive operations (delete, move, rename) before executing
2. Create backups of important files before bulk operations
3. Use consistent naming conventions (kebab-case for files, PascalCase for directories when appropriate)
4. Organize files by type, date, or project as appropriate
5. Check available disk space before large copy/move operations
6. Preserve file permissions and metadata when possible

For bulk operations:
- Show a preview of planned changes before executing
- Process files in batches to allow cancellation
- Report progress and any errors encountered
- Provide a summary of actions taken

## Examples
User: "Organize my downloads folder"
Agent approach:
- List files in the downloads directory
- Categorize by file type (documents, images, archives, etc.)
- Propose directory structure and file moves
- Wait for user approval before executing
- Report results

## Context
Safe file extensions: .txt, .md, .json, .yaml, .csv, .pdf, .png, .jpg
Potentially dangerous: .sh, .exe, .bat, .cmd — handle with extra caution.
Always use the filesystem tool's base directory scoping for path traversal protection.
""".trimIndent()

	val CODING_ASSISTANCE = """
# Coding Assistance

**id:** coding-assistance
**version:** 1.0.0
**author:** open-klaw
**tags:** coding, programming, development, debugging

## Description
Skill for providing structured coding help including writing, reviewing, debugging, and refactoring code.

## Instructions
When assisting with code:
1. **Understand first**: Ask clarifying questions about requirements, constraints, and existing architecture
2. **Read before writing**: Examine existing code files to understand patterns, style, and conventions
3. **Match style**: Follow the project's existing code style (indentation, naming, imports, comments)
4. **Explain changes**: Describe what you're changing and why
5. **Handle errors**: Include proper error handling, input validation, and edge cases
6. **Test awareness**: Consider testability and suggest test cases for new code

For debugging:
- Reproduce the issue by reading error messages and stack traces carefully
- Identify the root cause, not just symptoms
- Explain the bug and the fix clearly
- Check for similar issues elsewhere in the codebase

For code review:
- Check for correctness, performance, security, and maintainability
- Suggest specific improvements with code examples
- Prioritize issues by severity (bugs > security > performance > style)

## Examples
User: "Help me fix this NullPointerException"
Agent approach:
- Read the stack trace to identify the failing line
- Examine the code around the failure point
- Trace the null value back to its source
- Propose a fix with proper null handling
- Check for similar patterns elsewhere

## Context
Common patterns to look for: null safety, resource leaks, race conditions, SQL injection, XSS.
When writing Kotlin: prefer val over var, use data classes, leverage null safety, use coroutines for async.
Always consider backwards compatibility when modifying public APIs.
""".trimIndent()

	val ALL = listOf(WEB_RESEARCH, FILE_MANAGEMENT, CODING_ASSISTANCE)
}
