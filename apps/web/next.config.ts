import type { NextConfig } from "next";

// The API is bound to the loopback interface and has no authentication yet
// (deferred to M8). Proxying through the Next.js server keeps the browser
// same-origin, so no CORS configuration and no credential ever reaches the client.
const apiOrigin = process.env.PATO_COMMIT_API_ORIGIN ?? "http://127.0.0.1:8080";

const nextConfig: NextConfig = {
  async rewrites() {
    return {
      beforeFiles: [
        { source: "/api/backend/:path*", destination: `${apiOrigin}/api/:path*` },
      ],
    };
  },
};

export default nextConfig;
