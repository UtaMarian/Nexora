// Mirrors the Central Administration API (backend: io.nexora.admin, io.nexora.migration).
import type { PrincipalRef, ShareLinkDto } from "../api/types";

export type JobStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";

export interface Job {
  id: string;
  type: string;
  title: string;
  status: JobStatus;
  progress: number;
  message?: string;
  params?: Record<string, unknown>;
  result?: Record<string, unknown>;
  createdBy?: PrincipalRef;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
  log?: string;
}

export const isRunning = (j: { status: JobStatus }) => j.status === "QUEUED" || j.status === "RUNNING";

export interface Health {
  component: string;
  status: "OK" | "WARNING" | "ERROR" | "DISABLED";
  detail?: string;
}

export interface SharingPolicy {
  anyoneLinksAllowed: boolean;
  maxLinkDays: number;
  mail: { enabled: boolean; server: string; waiting: number; failed: number };
  mailProblem?: string;
  links: ShareLinkDto[];
}

export interface SearchComponent {
  name: string;
  ok: boolean;
  detail?: string;
}

export interface SearchStatus {
  enabled: boolean;
  engine: SearchComponent;
  extractor: SearchComponent;
  indexing: number;
  reading: number;
  documents: Record<"OK" | "EMPTY" | "REJECTED" | "FAILED", number>;
  unread: { path: string; status: string; detail?: string; extractedAt: string }[];
}

export interface Overview {
  counts: Record<string, number>;
  storedBytes: number;
  versionBytes: number;
  health: Health[];
  server: Record<string, string | number>;
  runningJobs: Job[];
}

export interface SiteRow {
  id: string;
  name: string;
  path: string;
  title: string;
  description?: string;
  template?: string;
  files: number;
  bytes: number;
  createdAt: string;
  lastModified: string;
  owners: string[];
}

export interface BackupSite {
  name: string;
  title: string;
  file: string;
  sizeBytes: number;
  items: number;
  versions: number;
}

export interface Backup {
  id: string;
  kind: "SITE" | "FARM" | "UPLOAD";
  label: string;
  createdAt: string;
  createdBy: string;
  sites: BackupSite[];
  includesDirectory: boolean;
  sizeBytes: number;
}

export interface LogEntry {
  seq: number;
  time: string;
  level: string;
  logger: string;
  thread: string;
  message: string;
  error?: string;
}

export interface UserRow {
  id: string;
  login: string;
  displayName: string;
  email?: string;
  admin: boolean;
  enabled: boolean;
  directory: boolean;
  createdAt: string;
}

export type SpAuth = "NTLM" | "BASIC" | "BEARER" | "COOKIE" | "NONE";

export interface MigrationSource {
  id: string;
  name: string;
  baseUrl: string;
  auth: SpAuth;
  username?: string;
  domain?: string;
  hasSecret: boolean;
  createdAt: string;
}

export interface ScanList {
  id: string;
  title: string;
  url: string;
  baseTemplate: number;
  kind: "library" | "list" | "unsupported";
  itemCount: number;
  uniquePermissions: boolean;
  files?: number;
  bytes?: number;
  note?: string;
}

export interface ScanReport {
  scannedAt: string;
  deep: boolean;
  web: { title: string; url: string; template: string; created: string; lastModified: string };
  subwebs: { title: string; url: string }[];
  lists: ScanList[];
  totals: Record<string, number>;
  warnings: string[];
  issues: { url: string; issue: string }[];
}

export interface MigrationTask {
  id: string;
  sourceId: string;
  sourceWeb: string;
  targetSite: string;
  options: Record<string, unknown>;
  scan?: ScanReport;
  status: "NEW" | "SCANNED" | "COPIED" | "SYNCED" | "FAILED";
  lastJobId?: string;
  lastSyncAt?: string;
  items: number;
  createdAt: string;
}
