import Foundation
func repr(_ v: Any?) -> String {
  guard let v = v else { return "N" }
  if v is NSNull { return "NULL" }
  if let b = v as? Bool, type(of: v) == Bool.self { return "B:\(b)" }
  if let i = v as? Int, type(of: v) == Int.self { return "I:\(i)" }
  if let d = v as? Double, type(of: v) == Double.self { return "D:\(d)" }
  if let s = v as? String { return "S:\"" + s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\n", with: "\\n").replacingOccurrences(of: "\r", with: "\\r").replacingOccurrences(of: "\t", with: "\\t").replacingOccurrences(of: "\"", with: "\\\"") + "\"" }
  if let a = v as? [Any?] { return "[" + a.map(repr).joined(separator: ",") + "]" }
  if let o = v as? [String: Any?] { return "{" + o.keys.sorted().map { "\($0)=" + repr(o[$0]!) }.joined(separator: ",") + "}" }
  return "?\(type(of: v))"
}
MainActor.assumeIsolated {
  let args = CommandLine.arguments
  let varsData = try! Data(contentsOf: URL(fileURLWithPath: args[1]))
  let defs = try! JSONDecoder().decode([String: VariableDefinition].self, from: varsData)
  let store = VariableStore()
  try! store.defineVariables(defs)
  let ev = ExpressionEvaluator()
  ev.locale = Locale(identifier: "en_US")
  let parser = ExpressionParser()
  while let line = readLine() {
    if line.isEmpty { continue }
    do {
      let node = try parser.parse(line)
      let r = try ev.evaluate(node, context: VariableContext(store: store))
      print(repr(r))
    } catch {
      print("ERR:" + ((error as? LocalizedError)?.errorDescription ?? "\(error)"))
    }
  }
}
