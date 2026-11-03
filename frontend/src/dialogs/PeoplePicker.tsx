import { useEffect, useState } from "react";
import { Combobox, Option, Persona } from "@fluentui/react-components";
import { api } from "../api/client";
import type { PrincipalRef } from "../api/types";

/** Searches users and groups as you type. */
export function PeoplePicker(props: {
  onSelect: (p: PrincipalRef | null) => void;
  placeholder?: string;
  groupsOnly?: boolean;
  usersOnly?: boolean;
}) {
  const [query, setQuery] = useState("");
  const [options, setOptions] = useState<PrincipalRef[]>([]);

  useEffect(() => {
    let cancelled = false;
    const t = setTimeout(async () => {
      try {
        const res = await api<PrincipalRef[]>(`/api/v1/principals?q=${encodeURIComponent(query)}`);
        if (!cancelled) {
          setOptions(
            res.filter((p) => (props.groupsOnly ? p.kind === "GROUP" : props.usersOnly ? p.kind === "USER" : true)),
          );
        }
      } catch {
        if (!cancelled) setOptions([]);
      }
    }, 200);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [query, props.groupsOnly, props.usersOnly]);

  return (
    <Combobox
      freeform
      placeholder={props.placeholder ?? "Enter a name or group"}
      value={query}
      onInput={(e) => setQuery((e.target as HTMLInputElement).value)}
      onOptionSelect={(_, d) => {
        const p = options.find((o) => o.id === d.optionValue) ?? null;
        setQuery(p?.displayName ?? "");
        props.onSelect(p);
      }}
      style={{ minWidth: 240, flex: 1 }}
    >
      {options.map((p) => (
        <Option key={p.id} value={p.id} text={p.displayName}>
          <Persona
            name={p.displayName}
            secondaryText={p.kind === "GROUP" ? `Group · ${p.login}` : p.login}
            avatar={{ color: "colorful", shape: p.kind === "GROUP" ? "square" : "circular" }}
            size="small"
          />
        </Option>
      ))}
    </Combobox>
  );
}
