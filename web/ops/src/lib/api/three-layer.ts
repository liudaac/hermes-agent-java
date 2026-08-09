/**
 * Three-layer admin API - User / Space / Org management.
 *
 * Endpoints under /api/org/*, /api/spaces/*, /api/users/*, /api/improvement/*.
 * These complement the existing business portal API with the three-layer
 * main line intervention points.
 */
import { fetchJSON } from "./_base";

// ── Types ──────────────────────────────────────────────────

export interface UserProfile {
  userId: string;
  displayName: string;
  email: string | null;
  channelBindings: Record<string, string>;
  spaces: SpaceMembership[];
  capabilities: UserCapability;
  preferences: UserPreferences;
}

export interface UserCapability {
  personalSkills: string[];
  frequentTools: string[];
  shortcuts: Record<string, string>;
  hiddenCapabilities: string[];
}

export interface UserPreferences {
  language: string;
  responseStyle: string;
  tone: string;
  autoApproveSafe: boolean;
  maxContextChars: number;
  extra: Record<string, unknown>;
}

export interface SpaceMembership {
  spaceId: string;
  spaceName: string;
  role: string;
  joinedAt: number;
}

export interface SpaceOverview {
  spaceId: string;
  spaceName: string;
  memberCount: number;
  knowledgeCount: number;
  skillCount: number;
  toolCount: number;
  templateCount: number;
}

export interface SpaceCapability {
  installedSkills: string[];
  enabledTools: string[];
  toolConfigs: Record<string, string>;
  availableTemplates: string[];
}

export interface SpacePolicy {
  approvalModes: Record<string, string>;
  protectedPaths: string[];
  sandboxEnforced: boolean;
  maxConcurrentRuns: number;
  decayPolicy: string;
  allowUserOverride: boolean;
}

export interface SpaceMember {
  userId: string;
  displayName: string;
  role: string;
  joinedAt: number;
  lastActiveAt: number;
}

export interface KnowledgeEntry {
  id: string;
  title: string;
  content: string;
  category: string;
  tags: string[];
  authorId: string;
  createdAt: number;
  updatedAt: number;
}

export interface OrgOverview {
  org: {
    modelCatalog: unknown;
    billingSummary: Record<string, unknown>;
  };
  spaces: SpaceOverview[];
  users: { userId: string; displayName: string; spaceCount: number }[];
}

// ── API ────────────────────────────────────────────────────

export const threeLayerApi = {
  // ── Organization ──
  getOrgOverview: () => fetchJSON<OrgOverview>("/api/org/overview"),
  getOrgModels: () => fetchJSON<{ ok: boolean; providers: unknown[] }>("/api/org/models"),
  getOrgUsers: () =>
    fetchJSON<{ ok: boolean; users: UserProfile[]; total: number }>("/api/org/users"),
  getOrgSpaces: () =>
    fetchJSON<{ ok: boolean; spaces: SpaceOverview[]; total: number }>("/api/org/spaces"),
  getOrgBilling: () =>
    fetchJSON<{ ok: boolean; billing: Record<string, unknown> }>("/api/org/billing"),

  // ── Space ──
  getSpaceOverview: (spaceId: string) =>
    fetchJSON<{ ok: boolean; overview: SpaceOverview }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/overview`,
    ),
  getSpaceCapabilities: (spaceId: string) =>
    fetchJSON<{ ok: boolean; capabilities: SpaceCapability }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/capabilities`,
    ),
  installSpaceSkill: (spaceId: string, skillId: string) =>
    fetchJSON<{ ok: boolean; skillId: string }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/capabilities/skills`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ skillId }) },
    ),
  uninstallSpaceSkill: (spaceId: string, skillId: string) =>
    fetchJSON<{ ok: boolean; removed: string }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/capabilities/skills/${encodeURIComponent(skillId)}`,
      { method: "DELETE" },
    ),
  getSpaceKnowledge: (spaceId: string) =>
    fetchJSON<{ ok: boolean; entries: KnowledgeEntry[] }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/knowledge`,
    ),
  addSpaceKnowledge: (spaceId: string, payload: Partial<KnowledgeEntry>) =>
    fetchJSON<{ ok: boolean; entry: KnowledgeEntry }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/knowledge`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  updateSpaceKnowledge: (spaceId: string, entryId: string, payload: Partial<KnowledgeEntry>) =>
    fetchJSON<{ ok: boolean; entry: KnowledgeEntry }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/knowledge/${encodeURIComponent(entryId)}`,
      { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  deleteSpaceKnowledge: (spaceId: string, entryId: string) =>
    fetchJSON<{ ok: boolean }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/knowledge/${encodeURIComponent(entryId)}`,
      { method: "DELETE" },
    ),
  searchSpaceKnowledge: (spaceId: string, q: string) =>
    fetchJSON<{ ok: boolean; results: KnowledgeEntry[]; total: number }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/knowledge/search?q=${encodeURIComponent(q)}`,
    ),
  getSpacePolicy: (spaceId: string) =>
    fetchJSON<{ ok: boolean; policy: SpacePolicy }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/policies`,
    ),
  updateSpacePolicy: (spaceId: string, policy: Partial<SpacePolicy>) =>
    fetchJSON<{ ok: boolean; policy: SpacePolicy }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/policies`,
      { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(policy) },
    ),
  getSpaceMembers: (spaceId: string) =>
    fetchJSON<{ ok: boolean; members: SpaceMember[] }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/members`,
    ),
  addSpaceMember: (spaceId: string, userId: string, displayName?: string, role?: string) =>
    fetchJSON<{ ok: boolean; member: SpaceMember }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/members`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ userId, displayName, role }) },
    ),
  removeSpaceMember: (spaceId: string, userId: string) =>
    fetchJSON<{ ok: boolean }>(
      `/api/spaces/${encodeURIComponent(spaceId)}/members/${encodeURIComponent(userId)}`,
      { method: "DELETE" },
    ),

  // ── User ──
  getUserProfile: (userId: string) =>
    fetchJSON<{ ok: boolean; profile: UserProfile }>(
      `/api/users/${encodeURIComponent(userId)}/profile`,
    ),
  updateUserProfile: (userId: string, profile: { displayName?: string; email?: string }) =>
    fetchJSON<{ ok: boolean; profile: UserProfile }>(
      `/api/users/${encodeURIComponent(userId)}/profile`,
      { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(profile) },
    ),
  getUserCapabilities: (userId: string) =>
    fetchJSON<{ ok: boolean; capabilities: UserCapability }>(
      `/api/users/${encodeURIComponent(userId)}/capabilities`,
    ),
  addUserCapability: (userId: string, payload: { type: string; value: string; alias?: string }) =>
    fetchJSON<{ ok: boolean; capabilities: UserCapability }>(
      `/api/users/${encodeURIComponent(userId)}/capabilities`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload) },
    ),
  removeUserCapability: (userId: string, type: string, value: string) =>
    fetchJSON<{ ok: boolean; capabilities: UserCapability }>(
      `/api/users/${encodeURIComponent(userId)}/capabilities/${encodeURIComponent(type)}/${encodeURIComponent(value)}`,
      { method: "DELETE" },
    ),
  getUserPreferences: (userId: string) =>
    fetchJSON<{ ok: boolean; preferences: UserPreferences }>(
      `/api/users/${encodeURIComponent(userId)}/preferences`,
    ),
  updateUserPreferences: (userId: string, prefs: Partial<UserPreferences>) =>
    fetchJSON<{ ok: boolean; preferences: UserPreferences }>(
      `/api/users/${encodeURIComponent(userId)}/preferences`,
      { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(prefs) },
    ),
  getUserSpaces: (userId: string) =>
    fetchJSON<{ ok: boolean; spaces: SpaceMembership[] }>(
      `/api/users/${encodeURIComponent(userId)}/spaces`,
    ),

  // ── Improvement ──
  getImprovementSignals: (params: { scope?: string; userId?: string; spaceId?: string }) => {
    const qs = new URLSearchParams();
    if (params.scope) qs.set("scope", params.scope);
    if (params.userId) qs.set("userId", params.userId);
    if (params.spaceId) qs.set("spaceId", params.spaceId);
    return fetchJSON<{ ok: boolean; signals: unknown[]; scope: string }>(
      `/api/improvement/signals?${qs}`,
    );
  },
  getImprovementProposals: (scope?: string) => {
    const qs = scope ? `?scope=${scope}` : "";
    return fetchJSON<{ ok: boolean; proposals: unknown[]; scope: string }>(
      `/api/improvement/proposals${qs}`,
    );
  },
  getImprovementAdaptations: (userId: string) =>
    fetchJSON<{ ok: boolean; preferences: UserPreferences; capabilities: UserCapability }>(
      `/api/improvement/adaptations?userId=${encodeURIComponent(userId)}`,
    ),
};
