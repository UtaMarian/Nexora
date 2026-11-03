import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";

export interface OfficeCapabilities {
  enabled: boolean;
  available: boolean;
  edit: string[];
  view: string[];
}

export interface OfficeSession {
  actionUrl: string;
  accessToken: string;
  accessTokenTtl: number;
  canWrite: boolean;
  fileName: string;
}

/** Which formats the browser editor supports (cached; re-checked every few minutes). */
export function useOffice() {
  const q = useQuery({
    queryKey: ["office"],
    queryFn: () => api<OfficeCapabilities>("/api/v1/office"),
    staleTime: 5 * 60_000,
  });
  const caps = q.data;
  const ext = (name: string) => name.split(".").pop()?.toLowerCase() ?? "";
  return {
    canOpen: (name: string) => !!caps?.available && (caps.edit.includes(ext(name)) || caps.view.includes(ext(name))),
    canEdit: (name: string) => !!caps?.available && caps.edit.includes(ext(name)),
  };
}

export const officeRoute = (id: string) => `/office/${id}`;
