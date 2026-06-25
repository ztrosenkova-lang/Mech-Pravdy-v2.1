const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#D9FFFFFF', // Полупрозрачный белый фон
    borderRadius: 12,
    overflow: 'hidden',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: 'rgba(255, 255, 255, 0.9)', // Светлый фон шапки
    borderBottomWidth: 2,
    borderBottomColor: '#1A8A2E', // Зелёная рамка
  },
  headerTitle: {
    color: '#1A8A2E', // Фирменный зелёный
    fontSize: 16,
    fontWeight: 'bold',
  },
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  headerButton: {
    paddingHorizontal: 6,
    paddingVertical: 2,
  },
  headerButtonText: {
    fontSize: 16,
    color: '#1A8A2E', // Зелёный вместо серого
  },
  statusDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginLeft: 4,
  },
  statusGreen: {
    backgroundColor: '#1A8A2E', // Фирменный зелёный
  },
  statusRed: {
    backgroundColor: '#FF4444',
  },
  statusBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingVertical: 4,
    backgroundColor: 'rgba(255, 255, 255, 0.7)', // Полупрозрачный белый
    borderBottomWidth: 1,
    borderBottomColor: '#1A8A2E', // Зелёная рамка
  },
  // Стиль для текста статуса ожидания
  statusText: {
    color: '#1A8A2E', // Фирменный зелёный
    fontWeight: 'bold',
    textAlign: 'center',
    fontSize: 10,
  },
  chatContainer: {
    flex: 1,
    paddingHorizontal: 8,
    paddingVertical: 4,
    backgroundColor: '#D9FFFFFF', // Полупрозрачный белый
  },
  message: {
    marginVertical: 2,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 8,
    maxWidth: '90%',
  },
  userMessage: {
    alignSelf: 'flex-end',
    backgroundColor: '#1A8A2E', // Фирменный зелёный для сообщений пользователя
    borderBottomRightRadius: 2,
  },
  botMessage: {
    alignSelf: 'flex-start',
    backgroundColor: 'rgba(255, 255, 255, 0.9)', // Белый фон для ответов бота
    borderWidth: 1,
    borderColor: '#1A8A2E', // Зелёная рамка
    borderBottomLeftRadius: 2,
  },
  systemMessage: {
    alignSelf: 'center',
    backgroundColor: 'rgba(26, 138, 46, 0.15)', // Лёгкий зелёный фон
    borderWidth: 1,
    borderColor: '#1A8A2E', // Зелёная рамка
  },
  // Стиль для выводимого текста сообщений
  messageText: {
    color: '#1A8A2E', // Фирменный зелёный цвет текста
    fontSize: 14,
    fontFamily: 'monospace', // Системный моноширинный шрифт
    lineHeight: 18,
  },
  userText: {
    color: '#FFFFFF', // Белый текст на зелёном фоне
  },
  botText: {
    color: '#1A8A2E', // Зелёный текст на белом фоне
  },
  systemText: {
    color: '#1A8A2E', // Зелёный текст
    fontSize: 11,
    textAlign: 'center',
    fontWeight: 'bold',
  },
  loadingContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    paddingHorizontal: 12,
    alignSelf: 'flex-start',
  },
  loadingText: {
    color: '#1A8A2E', // Зелёный вместо серого
    fontSize: 12,
    marginLeft: 8,
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 6,
    backgroundColor: 'rgba(255, 255, 255, 0.9)', // Светлый фон
    borderTopWidth: 2,
    borderTopColor: '#1A8A2E', // Зелёная рамка
  },
  input: {
    flex: 1,
    color: '#1A8A2E', // Зелёный текст ввода
    fontSize: 14,
    paddingHorizontal: 12,
    paddingVertical: 6,
    backgroundColor: '#D9FFFFFF', // Полупрозрачный белый
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#1A8A2E', // Зелёная рамка
    maxHeight: 80,
  },
  sendButton: {
    marginLeft: 8,
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: '#1A8A2E', // Фирменный зелёный
    justifyContent: 'center',
    alignItems: 'center',
  },
  sendButtonDisabled: {
    backgroundColor: '#B0D9B0', // Светло-зелёный для неактивной кнопки
  },
  sendButtonText: {
    color: '#FFFFFF', // Белый текст
    fontSize: 18,
    marginLeft: 2,
  },
});
