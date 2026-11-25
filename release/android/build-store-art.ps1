param(
    [string] $Destination = (Join-Path $PSScriptRoot 'assets')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName WindowsBase, PresentationCore

$project = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
[xml] $vector = Get-Content -LiteralPath (Join-Path $project 'app/src/main/res/drawable/ic_launcher_foreground.xml') -Raw
[xml] $colors = Get-Content -LiteralPath (Join-Path $project 'app/src/main/res/values/colors.xml') -Raw
$group = $vector.vector.group
$path = $group.path
$androidNamespace = $vector.DocumentElement.GetNamespaceOfPrefix('android')
$ring = [Windows.Media.Geometry]::Parse($path.GetAttribute('pathData', $androidNamespace))
$originalScale = [double] $group.GetAttribute('scaleX', $androidNamespace)
$originalX = [double] $group.GetAttribute('translateX', $androidNamespace)
$originalY = [double] $group.GetAttribute('translateY', $androidNamespace)
$markBrush = [Windows.Media.SolidColorBrush]::new(
    [Windows.Media.ColorConverter]::ConvertFromString($path.GetAttribute('fillColor', $androidNamespace))
)
$brand = $colors.resources.color | Where-Object { $_.name -eq 'ic_launcher_background' }
$brandGreen = [Windows.Media.ColorConverter]::ConvertFromString($brand.InnerText)
$brightGreen = [Windows.Media.ColorConverter]::ConvertFromString('#4ADE80')
$darkGreen = [Windows.Media.ColorConverter]::ConvertFromString('#0C4A2A')
$nearBlack = [Windows.Media.ColorConverter]::ConvertFromString('#03160C')
$brandBrush = [Windows.Media.SolidColorBrush]::new($brandGreen)

function Draw-Mark($drawing, [double] $centerX, [double] $centerY, [double] $visibleSize) {
    # A 72 × 72 box represents the visible center of Android's 108 × 108 adaptive icon.
    $ratio = $visibleSize / 72
    $matrix = [Windows.Media.Matrix]::new(
        $originalScale * $ratio, 0, 0, $originalScale * $ratio,
        $centerX + ($originalX - 54) * $ratio,
        $centerY + ($originalY - 54) * $ratio
    )
    $drawing.PushTransform([Windows.Media.MatrixTransform]::new($matrix))
    $drawing.DrawGeometry($markBrush, $null, $ring)
    $drawing.Pop()
}

function Save-Png($visual, [int] $width, [int] $height, [Windows.Media.PixelFormat] $format, [string] $path) {
    $target = [Windows.Media.Imaging.RenderTargetBitmap]::new(
        $width, $height, 96, 96, [Windows.Media.PixelFormats]::Pbgra32
    )
    $target.Render($visual)
    $bitmap = [Windows.Media.Imaging.FormatConvertedBitmap]::new($target, $format, $null, 0)
    $encoder = [Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
    $stream = [IO.File]::Open($path, [IO.FileMode]::Create)
    try { $encoder.Save($stream) }
    finally { $stream.Dispose() }
}

[IO.Directory]::CreateDirectory($Destination) | Out-Null
$icon = [Windows.Media.DrawingVisual]::new()
$draw = $icon.RenderOpen()
$draw.DrawRectangle($brandBrush, $null, [Windows.Rect]::new(0, 0, 512, 512))
Draw-Mark $draw 256 256 512
$draw.Close()
Save-Png $icon 512 512 ([Windows.Media.PixelFormats]::Bgra32) (Join-Path $Destination 'icon-512.png')

$feature = [Windows.Media.DrawingVisual]::new()
$draw = $feature.RenderOpen()
$background = [Windows.Media.LinearGradientBrush]::new($darkGreen, $nearBlack, 18)
$draw.DrawRectangle($background, $null, [Windows.Rect]::new(0, 0, 1024, 500))

$center = [Windows.Point]::new(324, 250)
foreach ($size in 160, 133, 108) {
    $guide = [Windows.Media.SolidColorBrush]::new($brightGreen)
    $guide.Opacity = 0.14
    $draw.DrawEllipse($null, [Windows.Media.Pen]::new($guide, 1.4), $center, $size, $size)
}
$draw.DrawEllipse($brandBrush, $null, $center, 84, 84)
Draw-Mark $draw $center.X $center.Y 204

$wordmarkTypeface = [Windows.Media.Typeface]::new(
    [Windows.Media.FontFamily]::new('Segoe UI'), [Windows.FontStyles]::Normal,
    [Windows.FontWeights]::Bold, [Windows.FontStretches]::Normal
)
$culture = [Globalization.CultureInfo]::InvariantCulture
$white = [Windows.Media.Brushes]::White
$heading = [Windows.Media.FormattedText]::new(
    'butone', $culture, [Windows.FlowDirection]::LeftToRight, $wordmarkTypeface, 88, $white, 1.0
)
$vpn = [Windows.Media.FormattedText]::new(
    'VPN', $culture, [Windows.FlowDirection]::LeftToRight, $wordmarkTypeface, 32,
    [Windows.Media.SolidColorBrush]::new($brightGreen), 1.0
)
$draw.DrawText($heading, [Windows.Point]::new(454, 171))
$draw.DrawText($vpn, [Windows.Point]::new(462, 271))
$draw.Close()
Save-Png $feature 1024 500 ([Windows.Media.PixelFormats]::Bgr24) (Join-Path $Destination 'feature-1024x500.png')
