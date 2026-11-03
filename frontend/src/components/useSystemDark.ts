import { useEffect, useState } from "react";

const QUERY = "(prefers-color-scheme: dark)";

export function useSystemDark(): boolean {
  const [dark, setDark] = useState(() => window.matchMedia(QUERY).matches);
  useEffect(() => {
    const mq = window.matchMedia(QUERY);
    const on = (e: MediaQueryListEvent) => setDark(e.matches);
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, []);
  return dark;
}
