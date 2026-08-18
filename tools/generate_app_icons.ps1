param(
    [string]$SourcePath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Drawing

$projectRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($SourcePath)) {
    $SourcePath = Join-Path $projectRoot "branding\app-icon-source.jpg"
}
$SourcePath = (Resolve-Path -LiteralPath $SourcePath).Path
$sourceBitmap = [System.Drawing.Bitmap]::new($SourcePath)
$backgroundSamples = @(
    $sourceBitmap.GetPixel(24, 24),
    $sourceBitmap.GetPixel($sourceBitmap.Width - 25, 24),
    $sourceBitmap.GetPixel(24, $sourceBitmap.Height - 25),
    $sourceBitmap.GetPixel($sourceBitmap.Width - 25, $sourceBitmap.Height - 25)
)
$backgroundColor = [System.Drawing.Color]::FromArgb(
    [int](($backgroundSamples | Measure-Object -Property R -Average).Average),
    [int](($backgroundSamples | Measure-Object -Property G -Average).Average),
    [int](($backgroundSamples | Measure-Object -Property B -Average).Average)
)

function New-SquareIcon([int]$size) {
    $bitmap = [System.Drawing.Bitmap]::new(
        $size,
        $size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear($backgroundColor)
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality

        $sourceInset = [Math]::Max(2, [int][Math]::Round(
            [Math]::Min($sourceBitmap.Width, $sourceBitmap.Height) * 0.003
        ))
        $sourceWidth = $sourceBitmap.Width - 2 * $sourceInset
        $sourceHeight = $sourceBitmap.Height - 2 * $sourceInset
        $scale = [Math]::Min(
            $size / [double]$sourceWidth,
            $size / [double]$sourceHeight
        )
        $width = [int][Math]::Round($sourceWidth * $scale)
        $height = [int][Math]::Round($sourceHeight * $scale)
        $left = [int](($size - $width) / 2)
        $top = [int](($size - $height) / 2)
        $destination = [System.Drawing.Rectangle]::new($left, $top, $width, $height)
        $graphics.DrawImage(
            $sourceBitmap,
            $destination,
            $sourceInset,
            $sourceInset,
            $sourceWidth,
            $sourceHeight,
            [System.Drawing.GraphicsUnit]::Pixel
        )
    } finally {
        $graphics.Dispose()
    }

    $feather = [Math]::Max(1, [int][Math]::Round($size * 0.03))
    for ($index = 0; $index -lt $feather; $index++) {
        $sourceWeight = ($index + 1.0) / ($feather + 1.0)
        $rows = @(
            [int]($top + $index)
            [int]($top + $height - 1 - $index)
        )
        foreach ($y in $rows) {
            for ($x = 0; $x -lt $size; $x++) {
                $pixel = $bitmap.GetPixel($x, $y)
                $red = [int][Math]::Round(
                    $backgroundColor.R * (1.0 - $sourceWeight) + $pixel.R * $sourceWeight
                )
                $green = [int][Math]::Round(
                    $backgroundColor.G * (1.0 - $sourceWeight) + $pixel.G * $sourceWeight
                )
                $blue = [int][Math]::Round(
                    $backgroundColor.B * (1.0 - $sourceWeight) + $pixel.B * $sourceWeight
                )
                $bitmap.SetPixel($x, $y, [System.Drawing.Color]::FromArgb(255, $red, $green, $blue))
            }
        }
    }
    return $bitmap
}

function Resize-Bitmap([System.Drawing.Bitmap]$source, [int]$size) {
    $bitmap = [System.Drawing.Bitmap]::new(
        $size,
        $size,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
        $graphics.DrawImage($source, 0, 0, $size, $size)
    } finally {
        $graphics.Dispose()
    }
    return $bitmap
}

function Save-Png([System.Drawing.Bitmap]$bitmap, [string]$path) {
    $directory = Split-Path -Parent $path
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
}

function Get-PngBytes([System.Drawing.Bitmap]$bitmap) {
    $stream = [System.IO.MemoryStream]::new()
    try {
        $bitmap.Save($stream, [System.Drawing.Imaging.ImageFormat]::Png)
        return ,$stream.ToArray()
    } finally {
        $stream.Dispose()
    }
}

function New-QuickSettingsMaster {
    $colorMaster = New-SquareIcon 192
    $monochromeSubject = [System.Drawing.Bitmap]::new(
        192,
        192,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    $monochrome = [System.Drawing.Bitmap]::new(
        256,
        256,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    try {
        for ($y = 0; $y -lt 192; $y++) {
            for ($x = 0; $x -lt 192; $x++) {
                $pixel = $colorMaster.GetPixel($x, $y)
                $red = [int]$pixel.R - [int]$backgroundColor.R
                $green = [int]$pixel.G - [int]$backgroundColor.G
                $blue = [int]$pixel.B - [int]$backgroundColor.B
                $distance = [Math]::Sqrt($red * $red + $green * $green + $blue * $blue)
                $alpha = [int][Math]::Round(($distance - 22.0) * 4.0)
                $alpha = [Math]::Max(0, [Math]::Min(255, $alpha))
                $monochromeSubject.SetPixel(
                    $x,
                    $y,
                    [System.Drawing.Color]::FromArgb($alpha, 0, 0, 0)
                )
            }
        }
        $graphics = [System.Drawing.Graphics]::FromImage($monochrome)
        try {
            $graphics.Clear([System.Drawing.Color]::Transparent)
            $graphics.DrawImage($monochromeSubject, 32, 32, 192, 192)
        } finally {
            $graphics.Dispose()
        }
    } finally {
        $monochromeSubject.Dispose()
        $colorMaster.Dispose()
    }
    return $monochrome
}

function Write-Ico([string]$path) {
    $sizes = @(16, 24, 32, 48, 64, 128, 256)
    $images = @()
    foreach ($size in $sizes) {
        $bitmap = New-SquareIcon $size
        try {
            [byte[]]$bytes = Get-PngBytes $bitmap
            $images += [pscustomobject]@{ Size = $size; Bytes = $bytes }
        } finally {
            $bitmap.Dispose()
        }
    }

    $stream = [System.IO.File]::Create($path)
    $writer = [System.IO.BinaryWriter]::new($stream)
    try {
        $writer.Write([uint16]0)
        $writer.Write([uint16]1)
        $writer.Write([uint16]$images.Count)
        $offset = 6 + 16 * $images.Count
        foreach ($image in $images) {
            $dimension = if ($image.Size -eq 256) { 0 } else { $image.Size }
            $writer.Write([byte]$dimension)
            $writer.Write([byte]$dimension)
            $writer.Write([byte]0)
            $writer.Write([byte]0)
            $writer.Write([uint16]1)
            $writer.Write([uint16]32)
            $writer.Write([uint32]$image.Bytes.Length)
            $writer.Write([uint32]$offset)
            $offset += $image.Bytes.Length
        }
        foreach ($image in $images) {
            $writer.Write([byte[]]$image.Bytes)
        }
    } finally {
        $writer.Dispose()
        $stream.Dispose()
    }
}

function Write-BigEndianInt32([System.IO.BinaryWriter]$writer, [int]$value) {
    $writer.Write([byte](($value -shr 24) -band 0xff))
    $writer.Write([byte](($value -shr 16) -band 0xff))
    $writer.Write([byte](($value -shr 8) -band 0xff))
    $writer.Write([byte]($value -band 0xff))
}

function Write-Icns([string]$path) {
    $specs = @(
        @{ Type = "icp4"; Size = 16 },
        @{ Type = "icp5"; Size = 32 },
        @{ Type = "icp6"; Size = 64 },
        @{ Type = "ic07"; Size = 128 },
        @{ Type = "ic08"; Size = 256 },
        @{ Type = "ic09"; Size = 512 },
        @{ Type = "ic10"; Size = 1024 }
    )
    $images = @()
    $totalLength = 8
    foreach ($spec in $specs) {
        $bitmap = New-SquareIcon $spec.Size
        try {
            [byte[]]$bytes = Get-PngBytes $bitmap
            $images += [pscustomobject]@{ Type = $spec.Type; Bytes = $bytes }
            $totalLength += 8 + $bytes.Length
        } finally {
            $bitmap.Dispose()
        }
    }

    $stream = [System.IO.File]::Create($path)
    $writer = [System.IO.BinaryWriter]::new($stream)
    try {
        $writer.Write([System.Text.Encoding]::ASCII.GetBytes("icns"))
        Write-BigEndianInt32 $writer $totalLength
        foreach ($image in $images) {
            $writer.Write([System.Text.Encoding]::ASCII.GetBytes($image.Type))
            Write-BigEndianInt32 $writer (8 + $image.Bytes.Length)
            $writer.Write([byte[]]$image.Bytes)
        }
    } finally {
        $writer.Dispose()
        $stream.Dispose()
    }
}

try {
    $androidRes = Join-Path $projectRoot "androidApp\src\main\res"
    $androidSizes = @{
        "mipmap-hdpi" = @{ Launcher = 72; Adaptive = 162; Tile = 36 }
        "mipmap-xhdpi" = @{ Launcher = 96; Adaptive = 216; Tile = 48 }
        "mipmap-xxhdpi" = @{ Launcher = 144; Adaptive = 324; Tile = 72 }
        "mipmap-xxxhdpi" = @{ Launcher = 192; Adaptive = 432; Tile = 96 }
    }
    $quickSettingsMaster = New-QuickSettingsMaster
    try {
        foreach ($density in $androidSizes.Keys) {
            $target = Join-Path $androidRes $density
            foreach ($name in @("ic_launcher.png")) {
                $bitmap = New-SquareIcon $androidSizes[$density].Launcher
                try { Save-Png $bitmap (Join-Path $target $name) } finally { $bitmap.Dispose() }
            }
            foreach ($name in @("ic_launcher_background.png", "ic_launcher_foreground.png")) {
                $bitmap = New-SquareIcon $androidSizes[$density].Adaptive
                try { Save-Png $bitmap (Join-Path $target $name) } finally { $bitmap.Dispose() }
            }
            $tile = Resize-Bitmap $quickSettingsMaster $androidSizes[$density].Tile
            try { Save-Png $tile (Join-Path $target "ic_qs_tile.png") } finally { $tile.Dispose() }
        }
    } finally {
        $quickSettingsMaster.Dispose()
    }

    $playStore = New-SquareIcon 512
    try { Save-Png $playStore (Join-Path $androidRes "playstore_icon.png") } finally { $playStore.Dispose() }

    $desktopIcons = Join-Path $projectRoot "desktopApp\appIcons"
    $linux = New-SquareIcon 512
    try { Save-Png $linux (Join-Path $desktopIcons "LinuxIcon.png") } finally { $linux.Dispose() }
    Write-Ico (Join-Path $desktopIcons "WindowsIcon.ico")
    Write-Icns (Join-Path $desktopIcons "MacosIcon.icns")

    $iosIcons = Join-Path $projectRoot "iosApp\iosApp\Assets.xcassets\AppIcon.appiconset"
    $iosSizes = @{
        "AppIcon-20.png" = 20; "AppIcon-20@2x.png" = 40; "AppIcon-20@3x.png" = 60
        "AppIcon-29.png" = 29; "AppIcon-29@2x.png" = 58; "AppIcon-29@3x.png" = 87
        "AppIcon-40.png" = 40; "AppIcon-40@2x.png" = 80; "AppIcon-40@3x.png" = 120
        "AppIcon-60@2x.png" = 120; "AppIcon-60@3x.png" = 180
        "AppIcon-76.png" = 76; "AppIcon-76@2x.png" = 152; "AppIcon-83.5@2x.png" = 167
        "AppIcon-1024.png" = 1024
    }
    foreach ($name in $iosSizes.Keys) {
        $bitmap = New-SquareIcon $iosSizes[$name]
        try { Save-Png $bitmap (Join-Path $iosIcons $name) } finally { $bitmap.Dispose() }
    }
} finally {
    $sourceBitmap.Dispose()
}

Write-Host "Application icons generated from $SourcePath"
