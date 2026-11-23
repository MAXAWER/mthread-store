// Рисует иконку приложения (сумка со стрелкой) в PNG 1024×1024.
import AppKit
let size: CGFloat = 1024
let img = NSImage(size: NSSize(width: size, height: size))
img.lockFocus()
let inset: CGFloat = 100, r = (size - inset * 2) * 0.225
let bg = NSBezierPath(roundedRect: NSRect(x: inset, y: inset, width: size - inset * 2, height: size - inset * 2), xRadius: r, yRadius: r)
NSGradient(starting: NSColor(red: 0.20, green: 0.52, blue: 1, alpha: 1), ending: NSColor(red: 0.07, green: 0.36, blue: 0.86, alpha: 1))!.draw(in: bg, angle: -90)
let k = (size - inset * 2) / 108, ox = inset, oy = inset
func p(_ x: CGFloat, _ y: CGFloat) -> NSPoint { NSPoint(x: ox + x * k, y: oy + (108 - y) * k) }
NSColor.white.setFill()
let bag = NSBezierPath()
bag.move(to: p(34, 42)); bag.line(to: p(74, 42)); bag.line(to: p(70.5, 74)); bag.curve(to: p(66, 78), controlPoint1: p(70.2, 76.5), controlPoint2: p(68.5, 78))
bag.line(to: p(42, 78)); bag.curve(to: p(37.5, 74), controlPoint1: p(39.5, 78), controlPoint2: p(37.8, 76.5)); bag.close(); bag.fill()
NSColor.white.setStroke()
let handle = NSBezierPath(); handle.lineWidth = 4.5 * k; handle.lineCapStyle = .round
handle.move(to: p(44, 42)); handle.line(to: p(44, 37))
handle.appendArc(withCenter: p(54, 37), radius: 10 * k, startAngle: 180, endAngle: 0, clockwise: true)
handle.line(to: p(64, 42)); handle.stroke()
NSColor(red: 0.10, green: 0.42, blue: 0.93, alpha: 1).setStroke()
let arrow = NSBezierPath(); arrow.lineWidth = 4.5 * k; arrow.lineCapStyle = .round; arrow.lineJoinStyle = .round
arrow.move(to: p(54, 50)); arrow.line(to: p(54, 68)); arrow.move(to: p(46.5, 60.5)); arrow.line(to: p(54, 68)); arrow.line(to: p(61.5, 60.5)); arrow.stroke()
img.unlockFocus()
let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
