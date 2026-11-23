using System.Diagnostics;
using System.Drawing.Drawing2D;
using System.Reflection;

namespace Butone.Windows;

internal sealed class MainForm : Form
{
    private static readonly Color BackgroundTop = Color.FromArgb(18, 28, 34);
    private static readonly Color PanelColor = Color.FromArgb(29, 42, 48);
    private static readonly Color TextDim = Color.FromArgb(180, 199, 194);
    private static readonly Color Green = Color.FromArgb(34, 197, 94);
    private static readonly Color Amber = Color.FromArgb(245, 158, 11);
    private static readonly Color Red = Color.FromArgb(239, 68, 68);

    private readonly VpnController _controller = new();
    private readonly Label _title = MakeLabel(25, FontStyle.Bold, Color.White);
    private readonly Label _detail = MakeLabel(11, FontStyle.Regular, TextDim);
    private readonly Label _server = MakeLabel(15, FontStyle.Bold, Color.White);
    private readonly Label _timer = MakeLabel(10, FontStyle.Regular, TextDim);
    private readonly RoundButton _power = new();
    private readonly TableLayoutPanel _services = new();
    private readonly Dictionary<string, Label> _serviceLabels = new(StringComparer.Ordinal);
    private readonly System.Windows.Forms.Timer _clock = new() { Interval = 1000 };
    private readonly NotifyIcon _tray;
    private bool _allowClose;
    private VpnSnapshot _snapshot = VpnSnapshot.Off;

    public MainForm()
    {
        Text = "butone VPN";
        Icon = SystemIcons.Shield;
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(520, 700);
        ClientSize = new Size(520, 700);
        BackColor = BackgroundTop;
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 10f);
        AutoScaleMode = AutoScaleMode.Dpi;

        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            BackColor = BackgroundTop,
            ColumnCount = 1,
            RowCount = 8,
            Padding = new Padding(28, 22, 28, 18),
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 200));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 48));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 130));
        root.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));

        var header = new Panel { Dock = DockStyle.Fill };
        header.Controls.Add(new Label
        {
            Text = "butone",
            Font = new Font("Segoe UI", 23f, FontStyle.Bold),
            ForeColor = Color.White,
            AutoSize = true,
            Location = new Point(0, 0),
        });
        var version = Assembly.GetExecutingAssembly().GetName().Version;
        header.Controls.Add(new Label
        {
            Text = $"Windows 11  ·  {version?.Major}.{version?.Minor}.{version?.Build}",
            Font = new Font("Segoe UI", 9f),
            ForeColor = TextDim,
            AutoSize = true,
            Anchor = AnchorStyles.Top | AnchorStyles.Right,
            Location = new Point(305, 13),
        });
        root.Controls.Add(header, 0, 0);

        var powerHost = new Panel { Dock = DockStyle.Fill };
        _power.Size = new Size(172, 172);
        _power.Location = new Point((ClientSize.Width - 56 - _power.Width) / 2, 8);
        _power.Anchor = AnchorStyles.Top;
        _power.Font = new Font("Segoe UI", 19f, FontStyle.Bold);
        _power.ForeColor = Color.White;
        _power.FlatStyle = FlatStyle.Flat;
        _power.FlatAppearance.BorderSize = 0;
        _power.BackColor = Color.FromArgb(75, 85, 99);
        _power.Text = "ВКЛЮЧИТЬ";
        _power.Cursor = Cursors.Hand;
        _power.Click += ToggleClicked;
        powerHost.Controls.Add(_power);
        root.Controls.Add(powerHost, 0, 1);

        ConfigureCentered(_title);
        ConfigureCentered(_detail);
        ConfigureCentered(_server);
        ConfigureCentered(_timer);
        root.Controls.Add(_title, 0, 2);
        root.Controls.Add(_detail, 0, 3);
        root.Controls.Add(_server, 0, 4);

        _services.Dock = DockStyle.Fill;
        _services.BackColor = PanelColor;
        _services.Padding = new Padding(12);
        _services.ColumnCount = 2;
        _services.RowCount = 2;
        _services.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        _services.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        _services.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        _services.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        var serviceNames = new[] { "YouTube", "Telegram", "Instagram", "WhatsApp" };
        for (var index = 0; index < serviceNames.Length; index++)
        {
            var label = MakeLabel(10, FontStyle.Bold, TextDim);
            label.Text = $"○ {serviceNames[index]}";
            label.Dock = DockStyle.Fill;
            label.TextAlign = ContentAlignment.MiddleCenter;
            _serviceLabels[serviceNames[index]] = label;
            _services.Controls.Add(label, index % 2, index / 2);
        }
        root.Controls.Add(_services, 0, 5);
        root.Controls.Add(_timer, 0, 6);

        var report = new Button
        {
            Text = "Открыть отчёт о работе",
            Dock = DockStyle.Fill,
            FlatStyle = FlatStyle.Flat,
            ForeColor = TextDim,
            BackColor = PanelColor,
            Cursor = Cursors.Hand,
        };
        report.FlatAppearance.BorderColor = Color.FromArgb(60, 81, 87);
        report.Click += (_, _) => OpenReport();
        root.Controls.Add(report, 0, 7);
        Controls.Add(root);

        var trayMenu = new ContextMenuStrip();
        trayMenu.Items.Add("Открыть", null, (_, _) => RestoreWindow());
        trayMenu.Items.Add("Отключить и выйти", null, async (_, _) => await ExitFromTrayAsync());
        _tray = new NotifyIcon
        {
            Icon = SystemIcons.Shield,
            Text = "butone VPN",
            Visible = true,
            ContextMenuStrip = trayMenu,
        };
        _tray.DoubleClick += (_, _) => RestoreWindow();

        _controller.StateChanged += ControllerStateChanged;
        _clock.Tick += (_, _) => UpdateTimer();
        _clock.Start();
        FormClosing += ClosingAsync;
        Resize += (_, _) =>
        {
            if (WindowState == FormWindowState.Minimized) Hide();
        };
        ApplyState(VpnSnapshot.Off);
    }

    private async void ToggleClicked(object? sender, EventArgs e)
    {
        if (_snapshot.Phase is VpnPhase.Disconnected or VpnPhase.Error)
            await _controller.StartAsync();
        else
            await _controller.StopAsync();
    }

    private void ControllerStateChanged(VpnSnapshot snapshot)
    {
        if (IsDisposed) return;
        if (InvokeRequired)
        {
            BeginInvoke((Action)(() => ApplyState(snapshot)));
            return;
        }
        ApplyState(snapshot);
    }

    private void ApplyState(VpnSnapshot snapshot)
    {
        _snapshot = snapshot;
        _title.Text = snapshot.Title;
        _detail.Text = snapshot.Detail;
        _server.Text = snapshot.Node is null
            ? ""
            : $"{snapshot.Node}{(snapshot.DelayMs.HasValue ? $"  ·  {snapshot.DelayMs} мс" : "")}";
        _power.Text = snapshot.Phase switch
        {
            VpnPhase.Disconnected or VpnPhase.Error => "ВКЛЮЧИТЬ",
            VpnPhase.Connecting => "ОТМЕНА",
            _ => "ВЫКЛЮЧИТЬ",
        };
        _power.BackColor = snapshot.Phase switch
        {
            VpnPhase.Connected => Green,
            VpnPhase.Connecting or VpnPhase.Reconnecting => Amber,
            VpnPhase.Error => Red,
            _ => Color.FromArgb(75, 85, 99),
        };
        _tray.Text = snapshot.Phase == VpnPhase.Connected ? "butone VPN — подключён" : "butone VPN";
        UpdateServices(snapshot.Services ?? []);
        UpdateTimer();
    }

    private void UpdateServices(IReadOnlyList<ServiceResult> results)
    {
        var byName = results.ToDictionary(result => result.Name, StringComparer.Ordinal);
        foreach (var pair in _serviceLabels)
        {
            if (!byName.TryGetValue(pair.Key, out var result))
            {
                pair.Value.Text = $"○ {pair.Key}";
                pair.Value.ForeColor = TextDim;
            }
            else
            {
                pair.Value.Text = $"{(result.Available ? "✓" : "✕")} {pair.Key}";
                pair.Value.ForeColor = result.Available ? Green : Red;
            }
        }
    }

    private void UpdateTimer()
    {
        if (_snapshot.ConnectedAt is not { } connectedAt)
        {
            _timer.Text = _snapshot.Phase is VpnPhase.Connecting or VpnPhase.Reconnecting
                ? "Идёт автоматический поиск — ничего нажимать не нужно"
                : "Полный туннель для Windows 11";
            return;
        }
        var elapsed = DateTimeOffset.Now - connectedAt;
        _timer.Text = $"Подключено {Math.Max(0, (int)elapsed.TotalHours):00}:{elapsed.Minutes:00}:{elapsed.Seconds:00}";
    }

    private void OpenReport()
    {
        var path = _controller.ReportPath;
        var directory = Path.GetDirectoryName(path)!;
        Directory.CreateDirectory(directory);
        var arguments = File.Exists(path) ? $"/select,\"{path}\"" : $"\"{directory}\"";
        Process.Start(new ProcessStartInfo("explorer.exe", arguments) { UseShellExecute = true });
    }

    private void RestoreWindow()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    private async Task ExitFromTrayAsync()
    {
        await _controller.StopAsync();
        _allowClose = true;
        Close();
    }

    private async void ClosingAsync(object? sender, FormClosingEventArgs e)
    {
        if (_allowClose) return;
        e.Cancel = true;
        Enabled = false;
        try
        {
            await _controller.DisposeAsync();
        }
        finally
        {
            _clock.Stop();
            _tray.Visible = false;
            _tray.Dispose();
            _allowClose = true;
            Close();
        }
    }

    private static Label MakeLabel(float size, FontStyle style, Color color) => new()
    {
        AutoSize = false,
        Font = new Font("Segoe UI", size, style),
        ForeColor = color,
        BackColor = Color.Transparent,
    };

    private static void ConfigureCentered(Label label)
    {
        label.Dock = DockStyle.Fill;
        label.TextAlign = ContentAlignment.MiddleCenter;
    }
}

internal sealed class RoundButton : Button
{
    protected override void OnResize(EventArgs e)
    {
        base.OnResize(e);
        using var path = new GraphicsPath();
        path.AddEllipse(ClientRectangle);
        Region = new Region(path);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        base.OnPaint(e);
        using var pen = new Pen(Color.FromArgb(120, Color.White), 3f);
        e.Graphics.DrawEllipse(pen, 3, 3, Width - 7, Height - 7);
    }
}
