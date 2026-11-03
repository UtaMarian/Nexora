import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { FluentProvider, webDarkTheme, webLightTheme } from "@fluentui/react-components";
import { App } from "./App";
import { ApiError } from "./api/client";
import { useSystemDark } from "./components/useSystemDark";

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (count, err) => !(err instanceof ApiError && err.status >= 400 && err.status < 500) && count < 2,
      refetchOnWindowFocus: false,
    },
  },
});

function Root() {
  const dark = useSystemDark();
  return (
    <FluentProvider theme={dark ? webDarkTheme : webLightTheme} style={{ minHeight: "100%" }}>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </FluentProvider>
  );
}

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Root />
  </StrictMode>,
);
