export interface ProbeResult {
  loaded: boolean;
  version: string;
  addResult: number;
  error: string;
}

export function probe(): ProbeResult;
