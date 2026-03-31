// src/compile.ts
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "fs";
import { join } from "path";
import { homedir } from "os";
import { createHash } from "crypto";

const CACHE_DIR = join(homedir(), ".arena", "bin");
const NATIVE_DIR = join(import.meta.dir, "native");

function sha256(content: string): string {
  return createHash("sha256").update(content).digest("hex").slice(0, 16);
}

function ensureCacheDir() {
  mkdirSync(CACHE_DIR, { recursive: true });
}

/** Compile a C source to a shared dylib. Returns path to dylib. */
export async function compileC(name: string): Promise<string> {
  ensureCacheDir();
  const src = join(NATIVE_DIR, `${name}.c`);
  const dylib = join(CACHE_DIR, `${name}.dylib`);
  const hashFile = join(CACHE_DIR, `${name}.c.sha256`);

  const content = readFileSync(src, "utf-8");
  const hash = sha256(content);

  if (existsSync(dylib) && existsSync(hashFile)) {
    const cached = readFileSync(hashFile, "utf-8").trim();
    if (cached === hash) return dylib;
  }

  const proc = Bun.spawnSync({
    cmd: [
      "cc", "-shared", "-O2", "-o", dylib, src,
      "-framework", "CoreGraphics", "-framework", "ApplicationServices",
    ],
    stderr: "pipe",
  });

  if (proc.exitCode !== 0) {
    const stderr = proc.stderr.toString();
    throw new Error(`Failed to compile ${name}.c: ${stderr}`);
  }

  writeFileSync(hashFile, hash);
  return dylib;
}

/** Compile a Swift source to a binary. Returns path to binary. */
export async function compileSwift(name: string): Promise<string> {
  ensureCacheDir();
  const src = join(NATIVE_DIR, `${name}.swift`);
  const bin = join(CACHE_DIR, name);
  const hashFile = join(CACHE_DIR, `${name}.swift.sha256`);

  const content = readFileSync(src, "utf-8");
  const hash = sha256(content);

  if (existsSync(bin) && existsSync(hashFile)) {
    const cached = readFileSync(hashFile, "utf-8").trim();
    if (cached === hash) return bin;
  }

  const proc = Bun.spawnSync({
    cmd: ["swiftc", "-O", "-o", bin, src],
    stderr: "pipe",
  });

  if (proc.exitCode !== 0) {
    const stderr = proc.stderr.toString();
    throw new Error(`Failed to compile ${name}.swift: ${stderr}`);
  }

  writeFileSync(hashFile, hash);
  return bin;
}
