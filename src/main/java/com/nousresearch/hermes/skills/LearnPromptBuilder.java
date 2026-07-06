package com.nousresearch.hermes.skills;

/**
 * Builds the standards-guided prompt for the /learn command.
 *
 * <p>Aligned with the original Python Hermes {@code agent/learn_prompt.py}.
 * The /learn command is open-ended: the user describes a source (directory,
 * URL, "what we just did", pasted notes) and this builder produces ONE prompt
 * that instructs the live agent to:</p>
 * <ol>
 *   <li>Gather the named sources using existing tools (read_file, search_files,
 *       web_extract, conversation history)</li>
 *   <li>Author a single SKILL.md via skill_create / skill_write_file</li>
 * </ol>
 *
 * <p>There is no separate distillation engine — the agent does the work with
 * its existing toolset. This works identically on local, Docker, and remote
 * terminal backends.</p>
 */
public final class LearnPromptBuilder {

    private LearnPromptBuilder() {}

    /**
     * The Hermes skill-authoring standards, distilled into a single block.
     * Embedded in every /learn prompt so distilled skills match house style.
     *
     * <p>These are the HARDLINE rules a maintainer enforces in review.</p>
     */
    public static final String AUTHORING_STANDARDS = """
        Follow the Hermes skill-authoring standards exactly. These are the same
        HARDLINE rules a maintainer enforces in review:

        Frontmatter:
        - name: lowercase-hyphenated, <=64 chars, no spaces.
        - description: ONE sentence, **<=60 characters**, ends with a period. State the
          capability, not the implementation. No marketing words (powerful,
          comprehensive, seamless, advanced, robust). Do NOT repeat the skill name. If
          the description contains a colon, wrap the whole value in double quotes.
          This is the most-violated rule and it is NOT cosmetic: the system-prompt
          skill index truncates the description to 60 chars and loads it every
          session, so anything past char 60 is silently cut and never routes. After
          you write the description, COUNT the characters; if it is over 60, cut it
          down before saving — do not ship a sentence and hope.
            Good (<=60): `Search arXiv papers by keyword, author, or ID.`
            Bad (123):   `A comprehensive skill that lets the agent search arXiv for
                          academic papers using keywords, authors, and categories.`
        - version: 0.1.0
        - author: always the literal value `Hermes`. NEVER fill it from the host
          environment — the OS/login username, git config, or any identity you can
          probe must not be written. Skills get shared and published, so an
          environment-derived name is a privacy leak the user never opted into.
        - platforms: declare `[macos]`, `[linux]`, and/or `[windows]` IF the skill
          uses OS-bound primitives (osascript/apt/systemctl => the matching OS; /proc,
          os.setsid, signal.SIGKILL => linux; fcntl/termios => POSIX). Prefer fixing it
          cross-platform first (tempfile.gettempdir(), pathlib.Path, psutil); gate only
          when the dependency is genuinely platform-bound. Omit the field for portable
          skills.
        - metadata.hermes.tags: a few Capitalized, Relevant, Tags.

        Body section order (omit a section only if it genuinely has no content):
        1. "# <Human Title>" then a 2-3 sentence intro: what it does, what it does NOT
           do, and the key dependency stance (e.g. "stdlib only").
        2. "## When to Use" — bullet list of concrete trigger phrases.
        3. "## Prerequisites" — exact env vars, install steps, credentials.
        4. "## How to Run" — the canonical invocation, framed through Hermes tools.
        5. "## Quick Reference" — a flat command/endpoint list, no narration.
        6. "## Procedure" — numbered steps with copy-paste-exact commands.
        7. "## Pitfalls" — known limits, rate limits, things that look broken but aren't.
        8. "## Verification" — a single command/check that proves the skill worked.

        Hermes-tool framing (this is what makes it a skill, not shell docs):
        - Frame running scripts as "invoke through the `terminal` tool".
        - Reference Hermes tools by name in backticks: `terminal`, `read_file`,
          `write_file`, `search_files`, `patch`, `web_extract`, `web_search`,
          `vision_analyze`, `browser_navigate`, `delegate_task`, `image_generate`,
          `text_to_speech`, `cronjob`, `memory`, `skill_view`, `execute_code`.
        - Do NOT name shell utilities the agent already has wrapped: say `read_file`
          not cat/head/tail, `search_files` not grep/rg/find/ls, `patch` not sed/awk,
          `web_extract` not curl-to-scrape, `write_file` not echo>file or heredocs.
        - Third-party CLIs (ffmpeg, gh, an SDK) are fine inside a script file, but the
          prose still frames them as "invoke through the `terminal` tool". If the
          skill needs an MCP server, name it and document its setup in Prerequisites.

        Quality bar:
        - Prefer exact commands, endpoint URLs, function signatures, and config keys
          that appear VERBATIM in the source. NEVER invent flags, paths, or APIs — if
          you didn't see it in the source, don't write it.
        - Keep it tight and scannable: ~100 lines for a simple skill, ~200 for a
          complex one. Don't re-paste the source docs.
        - Don't write a router/index/hub skill that only points at other skills.
        - Larger scripts/parsers belong in a `scripts/` file (add via
          `skill_write_file`), referenced from SKILL.md by relative path — not
          inlined for the agent to re-type every run. References go in `references/`,
          templates in `templates/`.""";

    /**
     * Build the agent prompt for an open-ended /learn request.
     *
     * @param userRequest the free-text the user gave after /learn — a
     *                    description of the workflow, paths, URLs, or
     *                    "what I just did". Empty string means "learn from
     *                    this conversation".
     * @return a complete instruction the agent runs as a normal turn
     */
    public static String buildLearnPrompt(String userRequest) {
        String req = userRequest == null ? "" : userRequest.strip();
        if (req.isEmpty()) {
            req = "the workflow we just went through in this conversation — review "
                + "the steps taken and distill them into a reusable skill";
        }

        return """
            [/learn] The user wants you to learn a reusable skill from the \
            request below, and save it.

            THE REQUEST:
            %s

            The request is open-ended and may mix two kinds of content, in any \
            order: SOURCES to gather (directories, file paths, URLs, "what we \
            just did", pasted notes) AND REQUIREMENTS that shape the skill \
            (what to focus on, what to leave out, scope, naming, the angle to \
            take). Treat EVERY part of the request as load-bearing. In \
            particular, prose that comes after a path or link is NOT incidental \
            — it is the user telling you what they want from that source. A \
            request like `<url> focus on the auth flow, skip the deprecated \
            endpoints` means: gather the URL AND honor "focus on auth, skip \
            deprecated" as authoring requirements. Never fetch the first source \
            and ignore the rest.

            Do this:
            1. Gather every source the user named, using the tools you already \
            have — `read_file`/`search_files` for local files or directories, \
            `web_extract` for URLs, the current conversation history if they \
            referred to something you just did, and the text they pasted as-is. \
            If the request is ambiguous about scope, make a reasonable choice \
            and note it; do not stall.
            1b. Apply every requirement, focus, and constraint in the request to \
            the skill you author — these govern what the SKILL.md covers and \
            emphasizes, not just which sources you read.
            2. Author ONE SKILL.md and save it with the `skill_create` tool. \
            Pick a sensible name and description. If the procedure needs \
            a non-trivial script, add it under the skill's `scripts/` with \
            `skill_write_file` and reference it by relative path. Add \
            supporting documentation under `references/`.

            %s

            When done, tell the user the skill name, its category, and a \
            one-line summary of what it captured.""".formatted(req, AUTHORING_STANDARDS);
    }
}
