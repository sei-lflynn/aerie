import test from "node:test";
import assert from "assert";

test("Unit Test", async () => {
  await test("example test", async () => {
    assert.equal("hello world", "hello world");
  });
});
