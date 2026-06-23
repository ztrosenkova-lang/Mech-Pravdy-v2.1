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

const { width, height } = Dimensions.get('window');

const FloatingBrain = () => {
  const [context, setContext] = useState(null);
  const [isLoading, setIsLoading] = useState(false);
  const [isModelLoaded, setIsModelLoaded] = useState(false);
  const [messages, setMessages] = useState([]);
  const [inputText, setInputText] = useState('');
  const scrollViewRef = useRef();

  useEffect(() => {
    initializeBrain();
  }, []);

  const initializeBrain = async () => {
    try {
      setIsLoading(true);
      const llamaContext = await initLlama({
        model: '/storage/emulated/0/models/local_model.gguf',
        n_ctx: 2048,
        n_gpu_layers: 99,
        use_mlock: true,
      });

      setContext(llamaContext);
      setIsModelLoaded(true);
      
      setMessages([
        {
          id: 'welcome',
          role: 'assistant',
          content: '🧠 Привет, Батя! Я — МОЗГ. Готов работать с твоим чатом.',
        },
      ]);
    } catch (error) {
      console.error('Ошибка инициализации:', error);
      setMessages([
        {
          id: 'error',
          role: 'system',
          content: `❌ Ошибка загрузки модели: ${error.message}`,
        },
      ]);
    } finally {
      setIsLoading(false);
    }
  };

  const sendMessage = async () => {
    if (!inputText.trim() || !context || !isModelLoaded) return;

    const userMessage = {
      id: Date.now().toString(),
      role: 'user',
      content: inputText.trim(),
    };

    setMessages(prev => [...prev, userMessage]);
    setInputText('');
    setIsLoading(true);

    try {
      const result = await context.completion(
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
        <Text style={styles.statusText}>
          {isLoading ? '⏳ Думаю...' : isModelLoaded ? '✅ Модель готова' : '❌ Модель не загружена'}
        </Text>
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
            <ActivityIndicator size="small" color="#21A038" />
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
          placeholderTextColor="#666"
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
    backgroundColor: 'rgba(10, 10, 10, 0.95)',
    borderRadius: 12,
    overflow: 'hidden',
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#1A1A1A',
    borderBottomWidth: 1,
    borderBottomColor: '#21A038',
  },
  headerTitle: {
    color: '#21A038',
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
    color: '#888',
  },
  statusDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    marginLeft: 4,
  },
  statusGreen: {
    backgroundColor: '#21A038',
  },
  statusRed: {
    backgroundColor: '#FF4444',
  },
  statusBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingVertical: 4,
    backgroundColor: '#111',
    borderBottomWidth: 1,
    borderBottomColor: '#222',
  },
  statusText: {
    fontSize: 10,
    color: '#888',
  },
  chatContainer: {
    flex: 1,
    paddingHorizontal: 8,
    paddingVertical: 4,
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
    backgroundColor: '#21A038',
    borderBottomRightRadius: 2,
  },
  botMessage: {
    alignSelf: 'flex-start',
    backgroundColor: '#2A2A2A',
    borderBottomLeftRadius: 2,
  },
  systemMessage: {
    alignSelf: 'center',
    backgroundColor: 'rgba(255, 165, 0, 0.15)',
    borderWidth: 1,
    borderColor: 'rgba(255, 165, 0, 0.3)',
  },
  messageText: {
    fontSize: 13,
    lineHeight: 18,
  },
  userText: {
    color: '#FFFFFF',
  },
  botText: {
    color: '#E0E0E0',
  },
  systemText: {
    color: '#FFD700',
    fontSize: 11,
    textAlign: 'center',
  },
  loadingContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 8,
    paddingHorizontal: 12,
    alignSelf: 'flex-start',
  },
  loadingText: {
    color: '#888',
    fontSize: 12,
    marginLeft: 8,
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    paddingVertical: 6,
    backgroundColor: '#1A1A1A',
    borderTopWidth: 1,
    borderTopColor: '#222',
  },
  input: {
    flex: 1,
    color: '#FFFFFF',
    fontSize: 14,
    paddingHorizontal: 12,
    paddingVertical: 6,
    backgroundColor: '#222',
    borderRadius: 20,
    maxHeight: 80,
  },
  sendButton: {
    marginLeft: 8,
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: '#21A038',
    justifyContent: 'center',
    alignItems: 'center',
  },
  sendButtonDisabled: {
    backgroundColor: '#444',
  },
  sendButtonText: {
    color: '#FFFFFF',
    fontSize: 18,
    marginLeft: 2,
  },
});

export default FloatingBrain;
