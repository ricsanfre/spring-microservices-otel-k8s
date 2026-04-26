import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Required for the multi-stage Docker build (copies only the minimum needed to run)
  output: "standalone",
};

export default nextConfig;
