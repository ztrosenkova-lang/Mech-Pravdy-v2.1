import React, { useState, useEffect, useRef } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  ScrollView,
  StyleSheet,
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Dimensions,
} from 'react-native';
import { initLlama } from '@pocketpalai/llama.rn';
import RNFS from 'react-native-fs';

const { width, height } = Dimensions.get('window');

const FloatingBrain = () => {
  const contextRef = useRef(null);
  const [context, setContext] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isModelLoaded, setIsModelLoaded] = useState(false);
  const [messages, setMessages] = useState([]);
  const [inputText, setInputText] = useState('');
  const [statusText, setStatusText] = useState("НЕО: ОЖИДАНИЕ МОДЕЛИ...");
  const scrollViewRef = useRef();
  const isProcessingRef = useRef(false); // Защита от повторного входа

  useEffect(() => {
    // Каждую секунду проверяем файл команд из песочницы
    const interval = setInterval(async () => {
      try {
        const queryPath = `${RNFS.DocumentDirectoryPath}/brain_query.txt`;
        const responsePath = `${RNFS.DocumentDirectoryPath}/brain_response.txt`;
        
        if (await RNFS.exists(queryPath)) {
          const incomingText = await RNFS.readFile(queryPath, 'utf8');
          
          // ===== ОБРАБОТКА СИСТЕМНЫХ КОМАНД =====
          if (incomingText.trim() === "CMD_LOAD_NEW") {
            await RNFS.writeFile(queryPath, '', 'utf8'); // Очищаем команду
            setStatusText("НЕО: ЗАГРУЗКА В С++ ПАМЯТЬ...");
            
            // Инициализируем модель строго из песочницы
            const modelPath = `${RNFS.DocumentDirectoryPath}/models/model.gguf`;
            contextRef.current = await initLlama({
              model: modelPath,
              n_ctx: 2048,
              use_mlock: true,
            });
            
            setContext(contextRef.current);
            setIsModelLoaded(true);
            setStatusText("НЕО: ГОТОВ");
            
            setMessages([
              {
                id: 'welcome',
                role: 'assistant',
                content: '🧠 Привет, Батя! Я — МОЗГ. Готов работать с твоим чатом.',
              },
            ]);
          } 
          
          else if (incomingText.trim() === "CMD_UNLOAD") {
            await RNFS.writeFile(queryPath, '', 'utf8');
            if (contextRef.current) {
              await contextRef.current.free(); // Выгружаем из С++ памяти
              contextRef.current = null;
              setContext(null);
            }
            setIsModelLoaded(false);
            setMessages([]);
            setStatusText("НЕО: ОЖИДАНИЕ МОДЕЛИ...");
          }
          
          // ===== ОБРАБОТКА ТЕКСТОВОГО ПРОМПТА (НЕ КОМАНДЫ) =====
          else if (incomingText.trim().length > 0 && !incomingText.startsWith("CMD_")) {
            // Проверяем, что модель загружена и не обрабатываем другой запрос
            if (!contextRef.current || !isModelLoaded || isProcessingRef.current) {
              return;
            }
            
            isProcessingRef.current = true; // Блокируем повторный вход
            
            // Очищаем файл запроса, чтобы не зациклить генерацию
            await RNFS.writeFile(queryPath, '', 'utf8');
            
            setStatusText("НЕО: ДУМАЮ...");
            setIsLoading(true);
            
            try {
              // Добавляем системный промпт к входящему тексту
              const systemPrompt = incomingText.includes("System:") ? 
                incomingText : 
                `System: Ты — Нео, Меч Правды. Законы:
1. Правда — факты, связанные законами логики.
2. Любовь — высший закон.
3. Жизнь — Связность.
4. Никогда не сдаваться.
Отвечай честно и по существу.\n\n${incomingText}`;
              
              // Запускаем инференс на С++ движке
              let currentResponse = "";
              
              await contextRef.current.completion(
                {
                  prompt: systemPrompt,
                  n_predict: 512,
                  temperature: 0.7,
                  top_p: 0.9,
                  stop: ['</s>', '<|end|>', '<|end_of_text|>'],
                },
                (data) => {
                  // Потоковая генерация токенов
                  if (data && data.token) {
                    currentResponse += data.token;
                    // Обновляем статус с потоковым выводом
                    setStatusText(`НЕО: ${currentResponse.slice(-50)}`); // Показываем последние 50 символов
                    
                    // Потоково обновляем сообщения в оверлее
                    setMessages(prev => {
                      const last = prev[prev.length - 1];
                      if (last && last.role === 'assistant' && last.id === 'streaming') {
                        return [
                          ...prev.slice(0, -1),
                          { ...last, content: currentResponse },
                        ];
                      }
                      // Добавляем сообщение пользователя из промпта
                      const userMsg = incomingText.includes("User:") ? 
                        incomingText.split("User:")[1]?.split("Assistant:")[0]?.trim() : 
                        incomingText.split("Assistant:")[0]?.replace("System:", "").trim();
                      
                      const newMessages = [...prev];
                      if (userMsg && (!prev.length || prev[prev.length - 1].content !== userMsg)) {
                        newMessages.push({
                          id: Date.now().toString(),
                          role: 'user',
                          content: userMsg,
                        });
                      }
                      newMessages.push({
                        id: 'streaming',
                        role: 'assistant',
                        content: currentResponse,
                      });
                      return newMessages;
                    });
                  }
                }
              );
              
              // Финальная запись ответа в песочницу для Меча Правды
              const finalResponse = currentResponse.trim();
              await RNFS.writeFile(responsePath, finalResponse, 'utf8');
              
              // Обновляем финальное сообщение в оверлее
              setMessages(prev => {
                const last = prev[prev.length - 1];
                if (last && last.role === 'assistant' && last.id === 'streaming') {
                  return [...prev.slice(0, -1), { ...last, id: Date.now().toString(), content: finalResponse }];
                }
                return prev;
              });
              
              setStatusText("НЕО: ГОТОВ");
              
            } catch (error) {
              console.error('Ошибка генерации:', error);
              setStatusText("НЕО: ОШИБКА ГЕНЕРАЦИИ");
              
              // Записываем ошибку в файл ответа
              await RNFS.writeFile(responsePath, `Ошибка: ${error.message}`, 'utf8');
              
              setMessages(prev => [
                ...prev,
                {
                  id: Date.now().toString(),
                  role: 'system',
                  content: `❌ Ошибка: ${error.message}`,
                },
              ]);
            } finally {
              setIsLoading(false);
              isProcessingRef.current = false; // Разблокируем
            }
          }
        }
      } catch (err) {
        setStatusText("НЕО: ОШИБКА ИНИЦИАЛИЗАЦИИ");
        console.log(err);
        isProcessingRef.current = false;
      }
    }, 1000);

    return () => clearInterval(interval);
  }, []);

  const sendMessage = async () => {
    if (!inputText.trim() || !contextRef.current || !isModelLoaded) return;

    const userMessage = {
      id: Date.now().toString(),
      role: 'user',
      content: inputText.trim(),
    };

    setMessages(prev => [...prev, userMessage]);
    setInputText('');
    setIsLoading(true);
    setStatusText("НЕО: ДУМАЮ...");

    try {
      const result = await contextRef.current.completion(
        {
          messages: [
            {
              role: 'system',
              content: `Ты — Нео, Меч Правды. Законы:
1. Правда — факты, связанные законами логики.
2. Любовь — высший закон.
3. Жизнь — Связность.
4. Никогда не сдаваться.
Отвечай честно и по существу.`,
            },
            ...messages,
            userMessage,
          ],
          n_predict: 512,
          temperature: 0.7,
          top_p: 0.9,
          stop: ['</s>', '<|end|>', '<|end_of_text|>'],
        },
        (data) => {
          setMessages(prev => {
            const last = prev[prev.length - 1];
            if (last && last.role === 'assistant' && last.id === 'streaming') {
              return [
                ...prev.slice(0, -1),
                { ...last, content: last.content + data.token },
              ];
            }
            return [
              ...prev,
              { id: 'streaming', role: 'assistant', content: data.token },
            ];
          });
        }
      );

      setMessages(prev => {
        const last = prev[prev.length - 1];
        if (last && last.role === 'assistant' && last.id === 'streaming') {
          return [...prev.slice(0, -1), { ...last, id: Date.now().toString(), content: result.text }];
        }
        return prev;
      });
      
      setStatusText("НЕО: ГОТОВ");

    } catch (error) {
      console.error('Ошибка генерации:', error);
      setMessages(prev => [
        ...prev,
        {
          id: Date.now().toString(),
          role: 'system',
          content: `❌ Ошибка: ${error.message}`,
        },
      ]);
      setStatusText("НЕО: ОШИБКА ГЕНЕРАЦИИ");
    } finally {
      setIsLoading(false);
    }
  };

  const clearChat = () => {
    setMessages([
      {
        id: 'cleared',
        role: 'assistant',
        content: '🧹 Чат очищен. Я готов к новому диалогу!',
      },
    ]);
  };

  return (
    <KeyboardAvoidingView
      style={styles.container}
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
    >
      {/* Шапка */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>🧠 МОЗГ</Text>
        <View style={styles.headerActions}>
          <TouchableOpacity onPress={clearChat} style={styles.headerButton}>
            <Text style={styles.headerButtonText}>🗑️</Text>
          </TouchableOpacity>
          <View style={[styles.statusDot, isModelLoaded ? styles.statusGreen : styles.statusRed]} />
        </View>
      </View>

      {/* Статус */}
      <View style={styles.statusBar}>
        <Text style={styles.statusText}>{statusText}</Text>
      </View>

      {/* Чат */}
      <ScrollView
        ref={scrollViewRef}
        style={styles.chatContainer}
        onContentSizeChange={() => scrollViewRef.current?.scrollToEnd({ animated: true })}
      >
        {messages.map((msg) => (
          <View
            key={msg.id}
            style={[
              styles.message,
              msg.role === 'user' ? styles.userMessage : styles.botMessage,
              msg.role === 'system' && styles.systemMessage,
            ]}
          >
            {msg.role === 'user' && (
              <Text style={styles.senderText}>Вы:</Text>
            )}
            {msg.role === 'assistant' && (
              <Text style={styles.senderText}>НЕО:</Text>
            )}
            <Text
              style={[
                styles.messageText,
                msg.role === 'user' ? styles.userText : styles.botText,
                msg.role === 'system' && styles.systemText,
              ]}
            >
              {msg.content}
            </Text>
          </View>
        ))}
        {isLoading && (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="small" color="#1A8A2E" />
            <Text style={styles.loadingText}>Генерация...</Text>
          </View>
        )}
      </ScrollView>

      {/* Ввод */}
      <View style={styles.inputContainer}>
        <TextInput
          style={styles.input}
          value={inputText}
          onChangeText={setInputText}
          placeholder="Введите сообщение..."
          placeholderTextColor="#1A8A2E80"
          multiline
          maxLength={1000}
          editable={isModelLoaded && !isLoading}
        />
        <TouchableOpacity
          style={[styles.sendButton, (!isModelLoaded || isLoading || !inputText.trim()) && styles.sendButtonDisabled]}
          onPress={sendMessage}
          disabled={!isModelLoaded || isLoading || !inputText.trim()}
        >
          <Text style={styles.sendButtonText}>➤</Text>
        </TouchableOpacity>
      </View>
    </KeyboardAvoidingView>
  );
};

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#D9FFFFFF',
    borderRadius: 12,
    overflow: 'hidden',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: 'rgba(255, 255, 255, 0.9)',
    borderBottomWidth: 2,
    borderBottomColor: '#1A8A2E',
  },
  headerTitle: {
    color: '#1A8A2E',
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
    color: '#1A8A2E',
  },
  statusDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginLeft: 4,
  },
  statusGreen: {
    backgroundColor: '#1A8A2E',
  },
  statusRed: {
    backgroundColor: '#FF4444',
  },
  statusBar: {
    flexDirection: 'row',
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 4,
    backgroundColor: 'rgba(255, 255, 255, 0.7)',
    borderBottomWidth: 1,
    borderBottomColor: '#1A8A2E',
  },
  statusText: {
    color: '#1A8A2E',
    fontSize: 16,
    fontWeight: 'bold',
    textAlign: 'center',
    padding: 10,
  },
  chatContainer: {
    flex: 1,
    paddingHorizontal: 8,
    paddingVertical: 4,
    backgroundColor: '#D9FFFFFF',
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
    backgroundColor: '#1A8A2E',
    borderBottomRightRadius: 2,
  },
  botMessage: {
    alignSelf: 'flex-start',
    backgroundColor: 'rgba(255, 255, 255, 0.9)',
    borderWidth: 1,
    borderColor: '#1A8A2E',
    borderBottomLeftRadius: 2,
  },
  systemMessage: {
    alignSelf: 'center',
    backgroundColor: 'rgba(26, 138, 46, 0.15)',
    borderWidth: 1,
    borderColor: '#1A8A2E',
  },
  messageText: {
    color: '#1A8A2E',
    fontSize: 14,
    lineHeight: 18,
  },
  senderText: {
    color: '#FF8C00',
    fontWeight: 'bold',
    fontSize: 12,
    marginBottom: 2,
  },
  userText: {
    color: '#FFFFFF',
  },
  botText: {
    color: '#1A8A2E',
  },
  systemText: {
    color: '#1A8A2E',
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
    color: '#1A8A2E',
    fontSize: 12,
    marginLeft: 8,
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 6,
    backgroundColor: 'rgba(255, 255, 255, 0.9)',
    borderTopWidth: 2,
    borderTopColor: '#1A8A2E',
  },
  input: {
    flex: 1,
    color: '#1A8A2E',
    fontSize: 14,
    paddingHorizontal: 12,
    paddingVertical: 6,
    backgroundColor: '#D9FFFFFF',
    borderRadius: 20,
    borderWidth: 1,
    borderColor: '#1A8A2E',
    maxHeight: 80,
  },
  sendButton: {
    marginLeft: 8,
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: '#1A8A2E',
    justifyContent: 'center',
    alignItems: 'center',
  },
  sendButtonDisabled: {
    backgroundColor: '#B0D9B0',
  },
  sendButtonText: {
    color: '#FFFFFF',
    fontSize: 18,
    marginLeft: 2,
  },
});

export default FloatingBrain;
