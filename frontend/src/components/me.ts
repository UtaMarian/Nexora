import { createContext, useContext } from "react";
import type { Me, NodeDto, Right } from "../api/types";

export const MeContext = createContext<Me | null>(null);

export function useMe(): Me {
  const me = useContext(MeContext);
  if (!me) throw new Error("useMe outside of MeContext");
  return me;
}

export const can = (node: Pick<NodeDto, "myRights"> | undefined, right: Right) =>
  !!node && node.myRights.includes(right);
