declare module "@sinclair/typebox" {
  export const Type: any;
}

declare module "openclaw/plugin-sdk/plugin-entry" {
  export function definePluginEntry(entry: any): any;
}

declare const process: {
  env: Record<string, string | undefined>;
};
