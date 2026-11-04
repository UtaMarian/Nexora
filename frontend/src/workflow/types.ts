// Mirrors the workflow API (backend: io.nexora.workflow).
export type ApprovalKind = "APPROVAL" | "REVIEW";
export type ApprovalMode = "ANY" | "ALL" | "SEQUENTIAL";
export type ApprovalStatus = "PENDING" | "APPROVED" | "REJECTED" | "CHANGES_REQUESTED" | "CANCELLED";

export interface ApprovalStep {
  id: string;
  position: number;
  name: string;
  decision: "PENDING" | "APPROVED" | "REJECTED" | "SKIPPED";
  decidedBy?: string;
  decidedAt?: string;
  comment?: string;
  active: boolean;
}

export interface Approval {
  id: string;
  nodeId: string;
  itemName: string;
  path: string;
  nodeKind: string;
  kind: ApprovalKind;
  mode: ApprovalMode;
  title: string;
  message?: string;
  status: ApprovalStatus;
  outcome?: string;
  requestedBy: string;
  versionLabel?: string;
  createdAt: string;
  dueAt?: string;
  completedAt?: string;
  steps: ApprovalStep[];
  canDecide: boolean;
  canCancel: boolean;
}

export type RuleTrigger = "CREATED" | "CHANGED" | "APPROVED" | "REJECTED" | "DATE";

export interface RuleCondition {
  column: string;
  operator: "eq" | "ne" | "contains" | "gt" | "lt" | "empty" | "notEmpty";
  value?: string;
}

export interface RuleWho {
  kind: "PERSON" | "CREATOR" | "EDITOR" | "COLUMN";
  login?: string;
  displayName?: string;
  column?: string;
}

export type RuleActionType = "SEND_EMAIL" | "SET_VALUE" | "REQUEST_APPROVAL" | "REQUEST_REVIEW" | "MOVE_TO_FOLDER" | "CREATE_ITEM";

export interface RuleAction {
  type: RuleActionType;
  people?: RuleWho[];
  subject?: string;
  body?: string;
  column?: string;
  value?: string;
  mode?: ApprovalMode;
  dueDays?: number | null;
  folder?: string;
  list?: string;
  title?: string;
}

export interface RuleDefinition {
  trigger: RuleTrigger;
  dateColumn?: string | null;
  daysBefore?: number | null;
  conditions: RuleCondition[];
  actions: RuleAction[];
}

export interface Rule {
  id: string;
  name: string;
  enabled: boolean;
  definition: RuleDefinition;
  updatedAt: string;
}

export interface RuleRun {
  id: string;
  ruleId: string;
  path: string;
  status: "DONE" | "SKIPPED" | "FAILED";
  detail?: string;
  ranAt: string;
}

export const APPROVAL_STATUS: Record<ApprovalStatus, { label: string; color: "informative" | "success" | "danger" | "warning" | "subtle" }> = {
  PENDING: { label: "Waiting", color: "informative" },
  APPROVED: { label: "Approved", color: "success" },
  REJECTED: { label: "Rejected", color: "danger" },
  CHANGES_REQUESTED: { label: "Changes requested", color: "warning" },
  CANCELLED: { label: "Cancelled", color: "subtle" },
};

/** The badge for a node's latest request, from "KIND:STATUS". Cancelled requests show nothing. */
export function approvalBadge(value?: string) {
  if (!value) return null;
  const [kind, status] = value.split(":") as [ApprovalKind, ApprovalStatus];
  if (status === "CANCELLED") return null;
  const s = APPROVAL_STATUS[status];
  if (kind === "REVIEW" && status === "PENDING") return { label: "In review", color: s.color };
  if (kind === "REVIEW" && status === "APPROVED") return { label: "Reviewed", color: s.color };
  return s;
}
