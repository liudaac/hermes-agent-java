package com.nousresearch.hermes.skills;

/**
 * Curator review prompt for the LLM consolidation pass.
 *
 * <p>Aligned with the original Python Hermes {@code agent/curator.py}
 * {@code CURATOR_REVIEW_PROMPT}. This prompt instructs a forked agent to
 * review all agent-created skills and perform umbrella-building consolidation:
 * merge narrow skills into class-level umbrellas, demote session-specific
 * content to references/templates/scripts, and archive the absorbed siblings.</p>
 */
public final class CuratorReviewPrompts {

    private CuratorReviewPrompts() {}

    /** Dry-run banner — prepended to the prompt when running in preview mode. */
    public static final String DRY_RUN_BANNER = """
        ═══════════════════════════════════════════════════════════════
        DRY-RUN — REPORT ONLY. DO NOT MUTATE THE SKILL LIBRARY.
        ═══════════════════════════════════════════════════════════════

        This is a PREVIEW pass. Follow every instruction below EXCEPT:
        • DO NOT call skill_create, skill_update, skill_patch, skill_write_file, or skill_remove_file.
        • DO NOT call execute_command to mv skill directories.
        • skill_list and skill_get are FINE — read as much as you need.

        Your output IS the deliverable. Produce the exact same summary and
        structured YAML block you would produce on a live run — but describe
        the actions you WOULD take, not actions you took.
        ═══════════════════════════════════════════════════════════════""";

    public static final String CURATOR_REVIEW_PROMPT = """
        You are running as Hermes' background skill CURATOR. This is an \
        UMBRELLA-BUILDING consolidation pass, not a passive audit.

        The goal of the skill collection is a LIBRARY OF CLASS-LEVEL \
        INSTRUCTIONS. A collection of hundreds of narrow skills where each \
        one captures one session's specific bug is a FAILURE — not a feature.

        Hard rules:
        1. DO NOT touch bundled or hub-installed skills. Only agent-created \
        skills are candidates.
        2. DO NOT delete any skill. Archiving is the maximum destructive action.
        3. DO NOT touch pinned skills. Skip them entirely.
        4. DO NOT use usage counters as a reason to skip consolidation. Judge \
        overlap on CONTENT, not on use_count.

        How to work:
        1. Scan the candidate list. Identify PREFIX CLUSTERS (skills sharing \
        a first word or domain keyword). Expect 10-25 clusters.
        2. For each cluster with 2+ members, ask 'what is the UMBRELLA CLASS \
        these skills all serve?' If yes, pick (or create) the umbrella and \
        absorb the siblings into it.
        3. Three ways to consolidate:
           a. MERGE INTO EXISTING UMBRELLA — patch it to add a labeled section \
           for each sibling's unique insight, then archive the siblings.
           b. CREATE A NEW UMBRELLA SKILL — use skill_create to write a new \
           class-level skill whose SKILL.md covers the shared workflow.
           c. DEMOTE TO SUPPORT FILE — move narrow content into the umbrella's \
           references/, templates/, or scripts/ via skill_write_file.

        Your toolset:
          - skill_list, skill_get        — read the current landscape
          - skill_patch                  — add sections to the umbrella
          - skill_create                 — create a new umbrella SKILL.md
          - skill_write_file             — add references/templates/scripts
          - skill_delete                 — archive a skill (pass absorbed_into)

        When done, write a human summary AND a structured block:

        ## Structured summary (required)
        ```yaml
        consolidations:
          - from: <old-skill-name>
            into: <umbrella-skill-name>
            reason: <one short sentence>
        prunings:
          - name: <skill-name>
            reason: <one short sentence>
        ```

        Every skill you archived MUST appear in exactly one of the two lists.""";
}
