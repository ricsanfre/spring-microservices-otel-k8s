import type { Metadata } from "next";
import { Nav } from "@/app/components/nav";
import "./globals.css";

export const metadata: Metadata = {
  title: "E-Commerce",
  description: "E-Commerce microservice platform",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Nav />
        <main>{children}</main>
      </body>
    </html>
  );
}
