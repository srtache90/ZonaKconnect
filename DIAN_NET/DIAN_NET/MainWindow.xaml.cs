using System;
using System.IO;
using System.Text.RegularExpressions;
using System.Windows;
using Microsoft.Extensions.Configuration;
using Microsoft.Win32;
using DIAN_NET.DIANreference;

namespace DIAN_NET
{
    /// <summary>
    /// Lógica de interacción para MainWindow.xaml
    /// </summary>
    public partial class MainWindow : Window
    {
        private readonly string _certificatePath;
        private readonly string _certificatePassword;
        private readonly IConfiguration _configuration;

        public MainWindow()
        {
            InitializeComponent();
            
            // Cargar configuración desde appsettings.json
            var builder = new ConfigurationBuilder()
                .SetBasePath(AppDomain.CurrentDomain.BaseDirectory)
                .AddJsonFile("appsettings.json", optional: false, reloadOnChange: true);
            
            _configuration = builder.Build();
            
            // Obtener la ruta del certificado desde la configuración o usar la ruta por defecto
            var baseDirectory = AppDomain.CurrentDomain.BaseDirectory;
            var configCertPath = _configuration["DianConfig:Certificado:RutaPfx"];
            
            if (!string.IsNullOrWhiteSpace(configCertPath) && File.Exists(configCertPath))
            {
                _certificatePath = configCertPath;
            }
            else
            {
                _certificatePath = Path.Combine(baseDirectory, "Certificado.pfx");
                
                // Si no está en bin, intentar en la raíz del proyecto
                if (!File.Exists(_certificatePath))
                {
                    var projectRoot = Directory.GetParent(baseDirectory)?.Parent?.FullName;
                    if (projectRoot != null)
                    {
                        _certificatePath = Path.Combine(projectRoot, "Certificado.pfx");
                    }
                }
            }
            
            // Obtener la contraseña desde la configuración
            _certificatePassword = _configuration["DianConfig:Certificado:Password"] ?? "UCKqYWwwhE";
            
            // Configurar el ambiente por defecto desde la configuración
            var ambienteDefault = _configuration["DianConfig:Ambiente"] ?? "Habilitacion";
            foreach (System.Windows.Controls.ComboBoxItem item in cmbAmbiente.Items)
            {
                if (item.Tag?.ToString()?.Equals(ambienteDefault, StringComparison.OrdinalIgnoreCase) == true)
                {
                    cmbAmbiente.SelectedItem = item;
                    break;
                }
            }
        }
        
        private string GetAmbienteSeleccionado()
        {
            if (cmbAmbiente.SelectedItem is System.Windows.Controls.ComboBoxItem selectedItem)
            {
                return selectedItem.Tag?.ToString() ?? "Habilitacion";
            }
            return "Habilitacion";
        }

        private void BtnConsultarEstado_Click(object sender, RoutedEventArgs e)
        {
            if (string.IsNullOrWhiteSpace(txtTrackId.Text))
            {
                MessageBox.Show("Por favor ingrese un TrackId", "Error", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            var ambiente = GetAmbienteSeleccionado();
            var serviceUrl = ambiente == "Produccion" 
                ? "https://vpfe.dian.gov.co/WcfDianCustomerServices.svc"
                : "https://vpfe-hab.dian.gov.co/WcfDianCustomerServices.svc";

            try
            {
                txtResultado.Text = $"Consultando estado en ambiente: {ambiente}...\r\n";
                Application.Current.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.Background);

                using (var manager = new DianManager(serviceUrl, _certificatePath, _certificatePassword))
                {
                    var resultado = manager.ConsultarEstado(txtTrackId.Text, ambiente);
                    
                    txtResultado.Text = $"Estado consultado exitosamente (Ambiente: {ambiente}):\r\n" +
                                       $"IsValid: {resultado.IsValid}\r\n" +
                                       $"StatusCode: {resultado.StatusCode}\r\n" +
                                       $"StatusDescription: {resultado.StatusDescription}\r\n" +
                                       $"StatusMessage: {resultado.StatusMessage}\r\n" +
                                       $"XmlDocumentKey: {resultado.XmlDocumentKey}\r\n" +
                                       $"XmlFileName: {resultado.XmlFileName}";
                }
            }
            catch (Exception ex)
            {
                var mensajeError = $"Error al consultar estado en ambiente '{ambiente}':\r\n{ex.Message}\r\n\r\nDetalles:\r\n{ex}";
                txtResultado.Text = mensajeError;
                MessageBox.Show($"Error al consultar estado en ambiente '{ambiente}':\r\n{ex.Message}", 
                    "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private void BtnConsultarRangos_Click(object sender, RoutedEventArgs e)
        {
            // Nota: este botón fue reusado para la "Consulta de Adquirientes"
            // (antes era "Consultar Rangos").

            // 1) Limpieza antes de consultar
            txtResultadoNit.Text = string.Empty;
            txtResultadoRazonSocial.Text = string.Empty;
            txtResultadoCorreo.Text = string.Empty;
            txtResultado.Text = string.Empty;

            if (string.IsNullOrWhiteSpace(txtNit.Text))
            {
                MessageBox.Show("Por favor ingrese un NIT", "Error", MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            var ambiente = GetAmbienteSeleccionado();
            var serviceUrl = ambiente == "Produccion" 
                ? "https://vpfe.dian.gov.co/WcfDianCustomerServices.svc"
                : "https://vpfe-hab.dian.gov.co/WcfDianCustomerServices.svc";

            try
            {
                var nitEntrada = txtNit.Text;
                string nitLimpio = Regex.Replace(nitEntrada, @"[^\d]", ""); // Quita todo lo que no sea número
                // Si el NIT tiene 10 dígitos y el último es el DV, quítalo:
                if (nitLimpio.Length > 9) { nitLimpio = nitLimpio.Substring(0, nitLimpio.Length - 1); }

                if (string.IsNullOrWhiteSpace(nitLimpio))
                {
                    MessageBox.Show("El NIT no es válido. Solo se permiten dígitos.", "Error", MessageBoxButton.OK, MessageBoxImage.Warning);
                    return;
                }

                txtResultado.Text = $"Consultando adquiriente en ambiente: {ambiente}...\r\n";
                Application.Current.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.Background);

                using (var manager = new DianManager(serviceUrl, _certificatePath, _certificatePassword))
                {
                    var resultado = manager.ConsultarEmpresaDIAN(nitLimpio);

                    if (resultado == null)
                    {
                        var mensaje = "Empresa no encontrada o no registrada para Facturación Electrónica";
                        txtResultado.Text = mensaje;
                        MessageBox.Show(mensaje, "Consulta DIAN", MessageBoxButton.OK, MessageBoxImage.Information);
                        return;
                    }

                    var statusCode = resultado.StatusCode ?? string.Empty;
                    var statusDescription = resultado.StatusDescription ?? string.Empty;
                    var email = resultado.Email ?? string.Empty;

                    // Mensajes por código DIAN (según tu regla).
                    if (string.Equals(statusCode, "404", StringComparison.OrdinalIgnoreCase))
                    {
                        var mensaje = "Empresa no encontrada o no registrada para Facturación Electrónica";
                        txtResultado.Text = $"{mensaje}\r\nStatusCode: {statusCode}\r\nStatusDescription: {statusDescription}";
                        MessageBox.Show(txtResultado.Text, "Consulta DIAN", MessageBoxButton.OK, MessageBoxImage.Information);
                        return;
                    }

                    if (string.Equals(statusCode, "401", StringComparison.OrdinalIgnoreCase))
                    {
                        var mensaje = "Problema con tu certificado (401 Unauthorized). Verifica el .pfx y su configuración.";
                        txtResultado.Text = $"{mensaje}\r\nStatusCode: {statusCode}\r\nStatusDescription: {statusDescription}";
                        MessageBox.Show(txtResultado.Text, "Consulta DIAN", MessageBoxButton.OK, MessageBoxImage.Error);
                        return;
                    }

                    if (string.Equals(statusCode, "100", StringComparison.OrdinalIgnoreCase) &&
                        string.IsNullOrWhiteSpace(email))
                    {
                        var mensaje = "La empresa no tiene correo técnico registrado para Facturación Electrónica.";
                        txtResultado.Text = $"{mensaje}\r\nStatusCode: {statusCode}\r\nStatusDescription: {statusDescription}";
                        MessageBox.Show(txtResultado.Text, "Consulta DIAN", MessageBoxButton.OK, MessageBoxImage.Warning);
                        return;
                    }

                    // Éxito esperado (StatusCode "00")
                    if (string.Equals(statusCode, "00", StringComparison.OrdinalIgnoreCase))
                    {
                        txtResultadoNit.Text = nitLimpio;
                        txtResultadoRazonSocial.Text = statusDescription; // nombre / razón social
                        txtResultadoCorreo.Text = email;

                        txtResultado.Text =
                            $"Consulta DIAN exitosa.\r\n" +
                            $"StatusCode: {statusCode}\r\n" +
                            $"StatusDescription: {statusDescription}\r\n";
                        return;
                    }

                    // Caso genérico: cualquier otro StatusCode.
                    txtResultadoNit.Text = string.Empty;
                    txtResultadoRazonSocial.Text = string.Empty;
                    txtResultadoCorreo.Text = string.Empty;

                    var mensajeGenerico = $"No se pudo completar la consulta en DIAN.\r\nStatusCode: {statusCode}\r\nStatusDescription: {statusDescription}";
                    txtResultado.Text = mensajeGenerico;
                    MessageBox.Show(mensajeGenerico, "Consulta DIAN", MessageBoxButton.OK, MessageBoxImage.Information);
                }
            }
            catch (Exception ex)
            {
                // Fallback de mensajes cuando DIAN arroja excepción.
                if (ex.Message != null && ex.Message.Contains("401"))
                {
                    var mensaje = "Problema con tu certificado (401 Unauthorized). Verifica el .pfx y su configuración.";
                    txtResultado.Text = mensaje;
                    MessageBox.Show(mensaje, "Consulta DIAN", MessageBoxButton.OK, MessageBoxImage.Error);
                    return;
                }

                if (ex.Message != null && ex.Message.Contains("404"))
                {
                    var mensaje = "Empresa no encontrada o no registrada para Facturación Electrónica";
                    txtResultado.Text = mensaje;
                    MessageBox.Show(mensaje, "Consulta DIAN", MessageBoxButton.OK, MessageBoxImage.Information);
                    return;
                }

                txtResultado.Text = $"Error al consultar adquiriente:\r\n{ex.Message}";
                MessageBox.Show($"Error al consultar adquiriente:\r\n{ex.Message}",
                    "Error", MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        private void BtnEnviarXML_Click(object sender, RoutedEventArgs e)
        {
            // Abrir diálogo para seleccionar archivo ZIP
            var openFileDialog = new OpenFileDialog
            {
                Filter = "Archivos ZIP (*.zip)|*.zip|Todos los archivos (*.*)|*.*",
                Title = "Seleccionar archivo ZIP de factura",
                CheckFileExists = true,
                CheckPathExists = true
            };

            if (openFileDialog.ShowDialog() == true)
            {
                var ambiente = GetAmbienteSeleccionado();
                var serviceUrl = ambiente == "Produccion" 
                    ? "https://vpfe.dian.gov.co/WcfDianCustomerServices.svc"
                    : "https://vpfe-hab.dian.gov.co/WcfDianCustomerServices.svc";

                try
                {
                    // Leer el archivo ZIP como byte[]
                    byte[] zipData;
                    using (var fileStream = new FileStream(openFileDialog.FileName, FileMode.Open, FileAccess.Read))
                    {
                        zipData = new byte[fileStream.Length];
                        fileStream.Read(zipData, 0, (int)fileStream.Length);
                    }

                    var nombreArchivo = Path.GetFileName(openFileDialog.FileName);
                    
                    txtResultado.Text = $"Enviando factura '{nombreArchivo}' en ambiente: {ambiente}...\r\n";
                    Application.Current.Dispatcher.Invoke(() => { }, System.Windows.Threading.DispatcherPriority.Background);

                    using (var manager = new DianManager(serviceUrl, _certificatePath, _certificatePassword))
                    {
                        var respuesta = manager.EnviarFactura(zipData, nombreArchivo, ambiente);
                        
                        // Construir mensaje de respuesta detallado
                        var resultado = $"Factura enviada exitosamente (Ambiente: {ambiente}):\r\n" +
                                       $"==========================================\r\n" +
                                       $"StatusCode: {respuesta.StatusCode}\r\n" +
                                       $"StatusDescription: {respuesta.StatusDescription}\r\n" +
                                       $"IsValid: {respuesta.IsValid}\r\n" +
                                       $"StatusMessage: {respuesta.StatusMessage}\r\n" +
                                       $"XmlDocumentKey: {respuesta.XmlDocumentKey}\r\n" +
                                       $"XmlFileName: {respuesta.XmlFileName}\r\n";
                        
                        // Mostrar ApplicationResponse si está disponible
                        if (respuesta.XmlBytes != null && respuesta.XmlBytes.Length > 0)
                        {
                            try
                            {
                                var xmlResponse = System.Text.Encoding.UTF8.GetString(respuesta.XmlBytes);
                                resultado += $"\r\n--- ApplicationResponse (XmlBytes) ---\r\n" +
                                           $"{xmlResponse}\r\n";
                            }
                            catch
                            {
                                resultado += $"\r\n--- ApplicationResponse (XmlBytes) ---\r\n" +
                                           $"Tamaño: {respuesta.XmlBytes.Length} bytes (no se pudo convertir a texto)\r\n";
                            }
                        }
                        else if (respuesta.XmlBase64Bytes != null && respuesta.XmlBase64Bytes.Length > 0)
                        {
                            try
                            {
                                var xmlResponse = System.Text.Encoding.UTF8.GetString(respuesta.XmlBase64Bytes);
                                resultado += $"\r\n--- ApplicationResponse (XmlBase64Bytes) ---\r\n" +
                                           $"{xmlResponse}\r\n";
                            }
                            catch
                            {
                                resultado += $"\r\n--- ApplicationResponse (XmlBase64Bytes) ---\r\n" +
                                           $"Tamaño: {respuesta.XmlBase64Bytes.Length} bytes (no se pudo convertir a texto)\r\n";
                            }
                        }
                        
                        // Mostrar errores si existen
                        if (respuesta.ErrorMessage != null && respuesta.ErrorMessage.Length > 0)
                        {
                            resultado += $"\r\n--- Errores ---\r\n";
                            foreach (var error in respuesta.ErrorMessage)
                            {
                                resultado += $"- {error}\r\n";
                            }
                        }
                        
                        txtResultado.Text = resultado;
                        
                        // Mostrar mensaje de éxito
                        var mensajeExito = $"Factura enviada exitosamente.\r\n" +
                                         $"StatusCode: {respuesta.StatusCode}\r\n" +
                                         $"StatusDescription: {respuesta.StatusDescription}";
                        MessageBox.Show(mensajeExito, "Éxito", MessageBoxButton.OK, MessageBoxImage.Information);
                    }
                }
                catch (Exception ex)
                {
                    var mensajeError = $"Error al enviar factura en ambiente '{ambiente}':\r\n{ex.Message}\r\n\r\nDetalles:\r\n{ex}";
                    txtResultado.Text = mensajeError;
                    MessageBox.Show($"Error al enviar factura en ambiente '{ambiente}':\r\n{ex.Message}", 
                        "Error", MessageBoxButton.OK, MessageBoxImage.Error);
                }
            }
        }
    }
}
